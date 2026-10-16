const FIREBASE_JWK_URL = 'https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com';
const TOKEN_SCOPE = 'https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/firebase.database https://www.googleapis.com/auth/firebase.messaging';

const MEDIA_POLICY = Object.freeze({
  image: Object.freeze({ maxBytes: 8 * 1024 * 1024, ttlMs: 5 * 24 * 60 * 60 * 1000 }),
  audio: Object.freeze({ maxBytes: 8 * 1024 * 1024, ttlMs: 5 * 24 * 60 * 60 * 1000 }),
  file: Object.freeze({ maxBytes: 8 * 1024 * 1024, ttlMs: 5 * 24 * 60 * 60 * 1000 }),
  video: Object.freeze({ maxBytes: 8 * 1024 * 1024, ttlMs: 3 * 24 * 60 * 60 * 1000 })
});

let certCache = { value: null, expiresAt: 0 };
let googleTokenCache = { value: null, expiresAt: 0 };

function json(status, body, extra = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': 'no-store',
      ...extra
    }
  });
}

const STRICT_ALLOWED_ORIGINS = Object.freeze([
  'https://chatrok-4b121.web.app',
  'https://chatrok-4b121.firebaseapp.com',
  'http://localhost',
  'https://localhost',
  'capacitor://localhost',
  'http://127.0.0.1'
]);

function cors(request, env) {
  const origin = request.headers.get('origin') || '';
  const envOrigins = String(env.CHATROK_ALLOWED_ORIGINS || '')
    .split(',')
    .map((value) => value.trim())
    .filter(Boolean);

  const isLocalPort = /^https?:\/\/(localhost|127\.0\.0\.1):\d+$/.test(origin);
  const isExplicitAllowed = STRICT_ALLOWED_ORIGINS.includes(origin) || envOrigins.includes(origin) || isLocalPort;
  const isAllowed = !origin || isExplicitAllowed;

  if (origin && !isAllowed) {
    throw Object.assign(new Error('Origin tidak diizinkan.'), { code: 'chat/origin-denied' });
  }

  return {
    'access-control-allow-origin': origin || 'https://chatrok-4b121.web.app',
    'access-control-allow-methods': 'GET,POST,DELETE,OPTIONS',
    'access-control-allow-headers': 'Authorization,Content-Type,X-CHATROK-Media-Type,X-CHATROK-Expires-At,X-CHATROK-Recipient-UID',
    'access-control-max-age': '86400',
    vary: 'Origin'
  };
}

function base64UrlDecode(value) {
  const normalized = String(value || '').replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(String(value || '').length / 4) * 4, '=');
  const bytes = Uint8Array.from(atob(normalized), (char) => char.charCodeAt(0));
  return bytes;
}

function base64UrlEncode(bytes) {
  let binary = '';
  const view = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  for (let i = 0; i < view.length; i += 0x8000) binary += String.fromCharCode(...view.subarray(i, i + 0x8000));
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

function pemToBytes(pem) {
  const body = String(pem || '').replace(/-----BEGIN [^-]+-----/g, '').replace(/-----END [^-]+-----/g, '').replace(/\s+/g, '');
  return Uint8Array.from(atob(body), (char) => char.charCodeAt(0));
}

async function firebasePublicKeys(env) {
  if (env?.TEST_FIREBASE_PUBLIC_KEYS) return env.TEST_FIREBASE_PUBLIC_KEYS;
  if (certCache.value && Date.now() < certCache.expiresAt) return certCache.value;
  const url = env?.FIREBASE_JWK_URL || FIREBASE_JWK_URL;
  const response = await fetch(url, { headers: { accept: 'application/json' } });
  if (!response.ok) throw new Error(`Firebase public-key fetch gagal (${response.status}).`);
  const cache = response.headers.get('cache-control') || '';
  const maxAge = Number(/max-age=(\d+)/i.exec(cache)?.[1] || 300);
  const data = await response.json();
  const keys = Object.fromEntries((data.keys || []).filter((key) => key?.kid).map((key) => [key.kid, key]));
  if (!Object.keys(keys).length) throw new Error('Firebase public-key set kosong.');
  certCache = { value: keys, expiresAt: Date.now() + Math.max(60, maxAge) * 1000 };
  return certCache.value;
}

async function verifyFirebaseIdToken(token, projectId, env) {
  const parts = String(token || '').split('.');
  if (parts.length !== 3) throw new Error('Firebase ID token tidak valid.');
  const header = JSON.parse(new TextDecoder().decode(base64UrlDecode(parts[0])));
  const payload = JSON.parse(new TextDecoder().decode(base64UrlDecode(parts[1])));
  const now = Math.floor(Date.now() / 1000);
  if (header.alg !== 'RS256' || !header.kid) throw new Error('Algoritma token Firebase ditolak.');
  if (payload.aud !== projectId) throw new Error('Audience token Firebase salah.');
  if (payload.iss !== `https://securetoken.google.com/${projectId}`) throw new Error('Issuer token Firebase salah.');
  if (!payload.sub || String(payload.sub).length > 128) throw new Error('UID token Firebase tidak valid.');
  if (Number(payload.exp || 0) <= now || Number(payload.iat || 0) > now + 60) throw new Error('Firebase ID token kedaluwarsa/tidak valid.');
  const publicKeys = await firebasePublicKeys(env);
  const jwk = publicKeys[header.kid];
  if (!jwk) throw new Error('Signing key Firebase tidak ditemukan.');
  const key = await crypto.subtle.importKey('jwk', jwk, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['verify']);
  const ok = await crypto.subtle.verify('RSASSA-PKCS1-v1_5', key, base64UrlDecode(parts[2]), new TextEncoder().encode(`${parts[0]}.${parts[1]}`));
  if (!ok) throw new Error('Signature Firebase ID token tidak valid.');
  return payload;
}

async function serviceJwt(env) {
  const email = String(env.FIREBASE_SERVICE_ACCOUNT_EMAIL || '').trim();
  const privateKeyPem = String(env.FIREBASE_PRIVATE_KEY || '').replace(/\\n/g, '\n').trim();
  if (!email || !privateKeyPem) throw new Error('Firebase service account secret belum lengkap.');
  const now = Math.floor(Date.now() / 1000);
  const header = base64UrlEncode(new TextEncoder().encode(JSON.stringify({ alg: 'RS256', typ: 'JWT' })));
  const claims = base64UrlEncode(new TextEncoder().encode(JSON.stringify({
    iss: email,
    sub: email,
    aud: 'https://oauth2.googleapis.com/token',
    scope: TOKEN_SCOPE,
    iat: now,
    exp: now + 3600
  })));
  const input = `${header}.${claims}`;
  const key = await crypto.subtle.importKey('pkcs8', pemToBytes(privateKeyPem), { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']);
  const signature = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, new TextEncoder().encode(input));
  return `${input}.${base64UrlEncode(new Uint8Array(signature))}`;
}

async function googleAccessToken(env) {
  if (googleTokenCache.value && Date.now() < googleTokenCache.expiresAt - 60000) return googleTokenCache.value;
  const assertion = await serviceJwt(env);
  const response = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion })
  });
  const data = await response.json().catch(() => null);
  if (!response.ok || !data?.access_token) throw new Error(data?.error_description || 'OAuth service account gagal.');
  googleTokenCache = { value: data.access_token, expiresAt: Date.now() + Number(data.expires_in || 3600) * 1000 };
  return googleTokenCache.value;
}

function bearerToken(request) {
  const match = /^Bearer\s+(.+)$/i.exec(request.headers.get('authorization') || '');
  if (!match) throw Object.assign(new Error('Authorization Bearer Firebase ID token wajib tersedia.'), { code: 'chat/unauthenticated' });
  return match[1];
}

function requireEnv(env) {
  const projectId = String(env.FIREBASE_PROJECT_ID || 'chatrok-4b121').trim();
  return { projectId };
}

function requireChatMediaBucket(env) {
  const bucket = env.CHAT_MEDIA_BUCKET;
  if (!bucket || typeof bucket.put !== 'function' || typeof bucket.get !== 'function' || typeof bucket.delete !== 'function') {
    throw new Error('Binding R2 CHAT_MEDIA_BUCKET belum tersedia.');
  }
  return bucket;
}

function fsString(value) { return { stringValue: String(value ?? '') }; }
function fsBoolean(value) { return { booleanValue: !!value }; }
function fsTimestamp(date = new Date()) { return { timestampValue: date.toISOString() }; }

async function firestoreRequest(env, path, init = {}) {
  if (typeof env?.TEST_FIRESTORE_REQUEST === 'function') {
    return env.TEST_FIRESTORE_REQUEST(path, init);
  }
  const { projectId } = requireEnv(env);
  const access = await googleAccessToken(env);
  const response = await fetch(`https://firestore.googleapis.com/v1/projects/${encodeURIComponent(projectId)}/databases/(default)/documents/${path}`, {
    ...init,
    headers: {
      Authorization: `Bearer ${access}`,
      'content-type': 'application/json',
      ...(init.headers || {})
    }
  });
  return response;
}

async function upsertMembership(env, uid) {
  try {
    const fields = {
      uid: fsString(uid),
      role: fsString('user'),
      active: fsBoolean(true),
      updatedAt: fsTimestamp()
    };
    await firestoreRequest(env, `chatMembers/${encodeURIComponent(uid)}?updateMask.fieldPaths=uid&updateMask.fieldPaths=role&updateMask.fieldPaths=active&updateMask.fieldPaths=updatedAt`, {
      method: 'PATCH',
      body: JSON.stringify({ fields })
    });
  } catch (err) {
    console.warn('[CHATROK Worker] Firestore membership upsert fallback:', err);
  }
  return {
    uid,
    role: 'user',
    active: true,
    updatedAt: new Date().toISOString()
  };
}

async function authenticated(request, env) {
  const { projectId } = requireEnv(env);
  const token = bearerToken(request);
  const claims = await verifyFirebaseIdToken(token, projectId, env);
  return { token, claims, profile: { uid: claims.sub, role: 'user' } };
}

function mediaPolicy(type) {
  const normalized = String(type || '').toLowerCase();
  const cfg = MEDIA_POLICY[normalized];
  if (!cfg) throw new Error('Tipe media ditolak.');
  return { type: normalized, ...cfg };
}

function parseMediaObjectKey(objectKey) {
  const normalized = String(objectKey || '').trim();
  const match = /^media\/(image|audio|file|video)\/(\d{13})\/([^/]{1,128})\/([0-9a-f-]{36})\.bin$/i.exec(normalized);
  if (!match) throw new Error('Object key media Chat tidak valid.');
  const expiresAt = Number(match[2]);
  if (!Number.isSafeInteger(expiresAt) || expiresAt <= 0) throw new Error('Expiry object media Chat tidak valid.');
  return {
    objectKey: normalized,
    mediaType: match[1].toLowerCase(),
    expiresAt,
    senderUid: match[3]
  };
}

async function uploadMedia(request, env, identity) {
  const mediaTypeHeader = request.headers.get('x-chatrok-media-type');
  const expiresAtHeader = request.headers.get('x-chatrok-expires-at');
  const recipientUid = String(request.headers.get('x-chatrok-recipient-uid') || '').trim();

  // Strict Media Authorization: recipientUid is MANDATORY for direct chat media
  if (!recipientUid || recipientUid === identity.claims.sub) {
    throw Object.assign(new Error('recipientUid media wajib tersedia dan bukan pengirim.'), { code: 'chat/invalid-recipient' });
  }

  const cfg = mediaPolicy(mediaTypeHeader);
  const expiresAt = Number(expiresAtHeader || 0);
  const now = Date.now();
  if (!Number.isFinite(expiresAt) || expiresAt <= now || expiresAt > now + cfg.ttlMs + 5 * 60 * 1000) {
    throw new Error('expiresAt media tidak valid.');
  }
  const contentLength = Number(request.headers.get('content-length') || 0);
  if (contentLength && contentLength > cfg.maxBytes) {
    throw new Error('Ciphertext media melebihi batas Worker (8 MiB).');
  }
  const bytes = new Uint8Array(await request.arrayBuffer());
  if (!bytes.length || bytes.length > cfg.maxBytes) {
    throw new Error('Ukuran ciphertext media tidak valid atau melebihi batas (8 MiB).');
  }
  const bucket = requireChatMediaBucket(env);
  const objectKey = `media/${cfg.type}/${expiresAt}/${identity.claims.sub}/${crypto.randomUUID()}.bin`;
  await bucket.put(objectKey, bytes, {
    httpMetadata: { contentType: 'application/octet-stream' },
    customMetadata: {
      senderUid: identity.claims.sub,
      recipientUid,
      mediaType: cfg.type,
      expiresAt: String(expiresAt)
    }
  });
  const stored = await bucket.head(objectKey);
  if (!stored || Number(stored.size) !== bytes.length || stored.customMetadata?.senderUid !== identity.claims.sub || stored.customMetadata?.recipientUid !== recipientUid) {
    await bucket.delete(objectKey).catch(() => {});
    throw new Error('Verifikasi penyimpanan media Chat di R2 gagal.');
  }
  return { objectKey, expiresAt, bytes: bytes.length };
}

async function readMedia(request, env, identity, objectKey) {
  const descriptor = parseMediaObjectKey(objectKey);
  const { mediaType, expiresAt } = descriptor;
  mediaPolicy(mediaType);
  const bucket = requireChatMediaBucket(env);
  if (!expiresAt || Date.now() >= expiresAt) {
    await bucket.delete(descriptor.objectKey).catch(() => {});
    return new Response('Expired', { status: 410, headers: { 'cache-control': 'no-store' } });
  }
  const stored = await bucket.get(descriptor.objectKey);
  if (!stored) return new Response('Not found', { status: 404 });
  if (stored.customMetadata?.mediaType !== mediaType || Number(stored.customMetadata?.expiresAt) !== expiresAt || stored.customMetadata?.senderUid !== descriptor.senderUid) {
    throw new Error('Metadata media Chat di R2 tidak konsisten.');
  }

  // Strict participant check: FAIL CLOSED if recipientUid is missing
  const callerUid = identity.claims.sub;
  const senderUid = stored.customMetadata?.senderUid || descriptor.senderUid;
  const recipientUid = stored.customMetadata?.recipientUid || '';

  if (!recipientUid || (callerUid !== senderUid && callerUid !== recipientUid)) {
    throw Object.assign(new Error('Akses media ditolak: Anda bukan partisipan percakapan ini.'), { code: 'chat/access-denied' });
  }

  return new Response(stored.body, {
    status: 200,
    headers: {
      'content-type': 'application/octet-stream',
      'cache-control': 'private, no-store',
      'x-chatrok-expires-at': String(expiresAt),
      'x-chatrok-media-type': mediaType
    }
  });
}

async function deleteMedia(env, identity, objectKey) {
  const descriptor = parseMediaObjectKey(objectKey);
  if (descriptor.senderUid !== identity.claims.sub) {
    throw Object.assign(new Error('Hanya pengirim yang dapat menghapus media sebelum expiry.'), { code: 'chat/access-denied' });
  }
  await requireChatMediaBucket(env).delete(descriptor.objectKey);
  return true;
}

async function cleanupExpiredChatMedia(env, now = Date.now()) {
  const bucket = requireChatMediaBucket(env);
  const checkpointKey = 'maintenance/media-cleanup-cursor.json';
  const checkpointObject = await bucket.get(checkpointKey);
  const checkpoint = checkpointObject ? await checkpointObject.json() : null;
  let cursor = typeof checkpoint?.cursor === 'string' ? checkpoint.cursor : undefined;
  let scanned = 0;
  let deleted = 0;
  do {
    const page = await bucket.list({ prefix: 'media/', limit: 1000, ...(cursor ? { cursor } : {}) });
    const expiredKeys = [];
    for (const object of page.objects || []) {
      scanned += 1;
      try {
        if (parseMediaObjectKey(object.key).expiresAt <= now) expiredKeys.push(object.key);
      } catch (_) {
        expiredKeys.push(object.key);
      }
    }
    if (expiredKeys.length) {
      await bucket.delete(expiredKeys);
      deleted += expiredKeys.length;
    }
    cursor = page.truncated ? page.cursor : undefined;
    await bucket.put(checkpointKey, JSON.stringify({ cursor: cursor || null, updatedAt: now }));
  } while (cursor && scanned < 10000);
  return { scanned, deleted, hasMore: Boolean(cursor) };
}

function firestoreValue(field) {
  if (!field) return null;
  if ('stringValue' in field) return field.stringValue;
  if ('booleanValue' in field) return field.booleanValue;
  if ('integerValue' in field) return field.integerValue;
  if ('doubleValue' in field) return field.doubleValue;
  return null;
}

async function listFirestoreDocumentsPaginated(env, collectionPath, pageSize = 100) {
  const documents = [];
  let pageToken = '';
  do {
    const separator = collectionPath.includes('?') ? '&' : '?';
    const queryParams = `pageSize=${pageSize}` + (pageToken ? `&pageToken=${encodeURIComponent(pageToken)}` : '');
    const path = `${collectionPath}${separator}${queryParams}`;
    const response = await firestoreRequest(env, path, { method: 'GET' });
    if (response.status === 404) break;
    if (!response.ok) {
      const err = new Error(`Firestore list gagal (${response.status}) untuk ${collectionPath}`);
      err.status = response.status;
      throw err;
    }
    const data = await response.json().catch(() => null);
    if (Array.isArray(data?.documents)) {
      documents.push(...data.documents);
    }
    pageToken = data?.nextPageToken || '';
  } while (pageToken);
  return documents;
}

async function listPushTokens(env, uid) {
  try {
    const docs = await listFirestoreDocumentsPaginated(env, `chatPushTokens/${encodeURIComponent(uid)}/tokens`, 100);
    return docs
      .map((item) => ({ token: firestoreValue(item.fields?.token), active: firestoreValue(item.fields?.active) }))
      .filter((item) => item.token && item.active !== false)
      .map((item) => item.token);
  } catch (err) {
    if (err?.status === 404) return [];
    throw err;
  }
}

const PUSH_EVENT_TYPES = Object.freeze({
  MESSAGE_ACTIVITY: 'message_activity',
  VOICE_CALL: 'voice_call',
  VIDEO_CALL: 'video_call'
});

const CLOAKED_NOTIFICATION = Object.freeze({
  TITLE: 'SIMNI ADMINISTRASI KELAS',
  BODY: 'Database telah siap, segera lakukan backup',
  TAG: 'simni-admin-backup',
  ICON: '/assets/simni-logo.png'
});

const CALL_NOTIFICATION = Object.freeze({
  DEFAULT_TITLE: 'CHATROK',
  VOICE_BODY: 'Panggilan suara masuk',
  VIDEO_BODY: 'Panggilan video masuk',
  TAG: 'chatrok-call',
  ICON: '/assets/simni-logo.png'
});

function sanitizeCallerName(name) {
  const text = String(name || '').trim();
  if (!text || text.includes('@') || /^[^s@]+@[^s@]+.[^s@]+$/.test(text)) {
    return CALL_NOTIFICATION.DEFAULT_TITLE;
  }
  return text.slice(0, 50) || CALL_NOTIFICATION.DEFAULT_TITLE;
}

function assertZeroSensitivePayload(payload) {
  if (!payload || typeof payload !== 'object') {
    throw new Error('Payload push tidak valid.');
  }
  const forbiddenKeys = [
    'message', 'bodyText', 'plaintext', 'decrypted', 'text', 'caption',
    'lastMessage', 'preview', 'filename', 'originalName', 'email'
  ];
  const serialized = JSON.stringify(payload).toLowerCase();
  for (const key of forbiddenKeys) {
    if (payload[key] !== undefined) {
      throw new Error(`Kebocoran payload push terdeteksi: field terlarang "${key}" ditemukan.`);
    }
  }
  if (/[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}/i.test(serialized)) {
    throw new Error('Kebocoran payload push terdeteksi: alamat email ditemukan dalam payload.');
  }
}

async function sendFcm(env, token, payload) {
  const access = await googleAccessToken(env);
  const { projectId } = requireEnv(env);
  const response = await fetch(`https://fcm.googleapis.com/v1/projects/${encodeURIComponent(projectId)}/messages:send`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${access}`, 'content-type': 'application/json' },
    body: JSON.stringify({
      message: {
        token,
        notification: payload.notification,
        data: payload.data,
        android: payload.android,
        webpush: payload.webpush
      }
    })
  });
  return response.ok;
}

async function push(request, env, identity) {
  const body = await request.json().catch(() => ({}));
  const recipientUid = String(body.recipientUid || '').trim();
  if (!recipientUid || recipientUid === identity.claims.sub) {
    throw Object.assign(new Error('recipientUid push tidak valid.'), { code: 'chat/invalid-recipient' });
  }

  // Canonical eventType resolution across call-signalling, push client, worker
  const eventType = body.eventType || body.notificationType || body.type || PUSH_EVENT_TYPES.MESSAGE_ACTIVITY;
  let fcmPayload;

  if (eventType === PUSH_EVENT_TYPES.VOICE_CALL || eventType === PUSH_EVENT_TYPES.VIDEO_CALL) {
    // ----------------------------------------------------
    // AUTHORITATIVE CALL PUSH VERIFICATION
    // ----------------------------------------------------
    const callId = String(body.callId || '').trim();
    if (!callId) {
      throw Object.assign(new Error('callId wajib untuk notifikasi panggilan.'), { code: 'chat/missing-call-id' });
    }

    const callRes = await firestoreRequest(env, `callSessions/${encodeURIComponent(callId)}`, { method: 'GET' });
    if (!callRes.ok) {
      throw Object.assign(new Error('Sesi panggilan tidak ditemukan.'), { code: 'chat/call-not-found' });
    }
    const callDoc = await callRes.json();
    const callCallerUid = firestoreValue(callDoc.fields?.callerUid);
    const callCalleeUid = firestoreValue(callDoc.fields?.calleeUid);
    const callState = firestoreValue(callDoc.fields?.state);
    const callExpiresAt = Number(callDoc.fields?.expiresAt?.integerValue || callDoc.fields?.expiresAt?.doubleValue || 0);

    if (callCallerUid !== identity.claims.sub) {
      throw Object.assign(new Error('Otorisasi panggilan ditolak: Anda bukan caller.'), { code: 'chat/access-denied' });
    }
    if (callCalleeUid !== recipientUid) {
      throw Object.assign(new Error('Otorisasi panggilan ditolak: Recipient bukan callee.'), { code: 'chat/access-denied' });
    }
    if (callState !== 'ringing') {
      throw Object.assign(new Error('Panggilan sudah tidak aktif.'), { code: 'chat/call-expired' });
    }
    if (callExpiresAt && Date.now() > callExpiresAt) {
      throw Object.assign(new Error('Panggilan telah kedaluwarsa.'), { code: 'chat/call-expired' });
    }

    // Authoritative caller display name from publicProfiles (fallback to callerName or DEFAULT)
    let callerDisplayName = 'Pengguna';
    try {
      const profileRes = await firestoreRequest(env, `publicProfiles/${encodeURIComponent(identity.claims.sub)}`, { method: 'GET' });
      if (profileRes.ok) {
        const profileDoc = await profileRes.json();
        callerDisplayName = firestoreValue(profileDoc.fields?.displayName) || firestoreValue(profileDoc.fields?.username) || 'Pengguna';
      }
    } catch (_) {
      callerDisplayName = body.callerName || 'Pengguna';
    }

    const isVideo = eventType === PUSH_EVENT_TYPES.VIDEO_CALL;
    const callerName = sanitizeCallerName(callerDisplayName);
    const callBody = isVideo ? CALL_NOTIFICATION.VIDEO_BODY : CALL_NOTIFICATION.VOICE_BODY;

    fcmPayload = {
      notification: {
        title: callerName,
        body: callBody
      },
      data: {
        kind: 'chatrok-call',
        type: eventType,
        route: 'call',
        callId,
        callerUid: identity.claims.sub
      },
      android: {
        priority: 'high',
        notification: {
          channel_id: 'chatrok-calls',
          visibility: 'PUBLIC',
          sound: 'default',
          default_vibrate_timings: true
        }
      },
      webpush: {
        headers: { Urgency: 'high' },
        notification: {
          title: callerName,
          body: callBody,
          tag: CALL_NOTIFICATION.TAG,
          icon: CALL_NOTIFICATION.ICON,
          renotify: true
        }
      }
    };
  } else {
    // ----------------------------------------------------
    // AUTHORITATIVE MESSAGE PUSH VERIFICATION
    // ----------------------------------------------------
    const conversationId = String(body.conversationId || '').trim();
    if (!conversationId) {
      throw Object.assign(new Error('conversationId wajib untuk notifikasi pesan.'), { code: 'chat/missing-conversation-id' });
    }

    const convRes = await firestoreRequest(env, `chatConversations/${encodeURIComponent(conversationId)}`, { method: 'GET' });
    if (!convRes.ok) {
      throw Object.assign(new Error('Percakapan tidak ditemukan.'), { code: 'chat/conversation-not-found' });
    }
    const convDoc = await convRes.json();
    const participants = (convDoc.fields?.participants?.arrayValue?.values || []).map((v) => v.stringValue);
    if (!participants.includes(identity.claims.sub) || !participants.includes(recipientUid)) {
      throw Object.assign(new Error('Otorisasi pesan ditolak: Anda atau recipient bukan partisipan percakapan.'), { code: 'chat/access-denied' });
    }

    // 100% Cloaked Message Push: strictly constant title & body
    fcmPayload = {
      notification: {
        title: CLOAKED_NOTIFICATION.TITLE,
        body: CLOAKED_NOTIFICATION.BODY
      },
      data: {
        kind: 'chatrok-activity',
        type: PUSH_EVENT_TYPES.MESSAGE_ACTIVITY,
        route: 'chat',
        conversationId
      },
      android: {
        priority: 'normal',
        notification: {
          channel_id: 'chatrok-message-activity',
          visibility: 'PRIVATE',
          notification_count: 1
        }
      },
      webpush: {
        headers: { Urgency: 'normal' },
        notification: {
          title: CLOAKED_NOTIFICATION.TITLE,
          body: CLOAKED_NOTIFICATION.BODY,
          tag: CLOAKED_NOTIFICATION.TAG,
          icon: CLOAKED_NOTIFICATION.ICON,
          renotify: false
        }
      }
    };
  }

  assertZeroSensitivePayload(fcmPayload);

  const tokens = await listPushTokens(env, recipientUid);
  const results = await Promise.allSettled(tokens.map((token) => sendFcm(env, token, fcmPayload)));
  return { attempted: tokens.length, delivered: results.filter((item) => item.status === 'fulfilled' && item.value === true).length };
}

async function route(request, env) {
  const url = new URL(request.url);
  const corsHeaders = cors(request, env);
  if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: corsHeaders });

  // Public Avatar GET endpoint (no Firebase auth required so <img> tags load anywhere)
  if (url.pathname.startsWith('/v1/avatar/') && request.method === 'GET') {
    const targetUid = decodeURIComponent(url.pathname.slice('/v1/avatar/'.length)).split('?')[0].replace(/[^a-zA-Z0-9_-]/g, '');
    if (!targetUid) return json(400, { ok: false, message: 'UID avatar tidak valid.' }, corsHeaders);
    const bucket = requireChatMediaBucket(env);
    const objectKey = `avatars/${targetUid}.bin`;
    const object = await bucket.get(objectKey);
    if (!object) {
      return json(404, { ok: false, code: 'avatar-not-found', message: 'Foto profil belum dipasang.' }, corsHeaders);
    }
    const contentType = object.httpMetadata?.contentType || 'image/jpeg';
    const headers = new Headers(corsHeaders);
    headers.set('content-type', contentType);
    headers.set('cache-control', 'public, max-age=300, must-revalidate');
    if (object.httpEtag) headers.set('etag', object.httpEtag);
    return new Response(object.body, { status: 200, headers });
  }

  const identity = await authenticated(request, env);

  if (url.pathname === '/v1/avatar/upload' && request.method === 'POST') {
    const rawType = request.headers.get('content-type') || 'image/jpeg';
    const contentType = rawType.split(';')[0].trim().toLowerCase();
    if (!['image/jpeg', 'image/png', 'image/webp', 'image/gif'].includes(contentType)) {
      throw new Error('Format foto profil harus JPG, PNG, WEBP, atau GIF.');
    }
    const bytes = new Uint8Array(await request.arrayBuffer());
    if (!bytes.length || bytes.length > 5 * 1024 * 1024) {
      throw new Error('Ukuran foto profil tidak boleh kosong dan maksimal 5 MB.');
    }
    const bucket = requireChatMediaBucket(env);
    const objectKey = `avatars/${identity.claims.sub}.bin`;
    // Permanently overwrite: replaces old avatar directly in R2 bucket
    await bucket.put(objectKey, bytes, {
      httpMetadata: { contentType }
    });
    const avatarUrl = `${url.origin}/v1/avatar/${encodeURIComponent(identity.claims.sub)}?t=${Date.now()}`;
    return json(200, { ok: true, avatarUrl }, corsHeaders);
  }

  if (url.pathname === '/v1/avatar' && request.method === 'DELETE') {
    const bucket = requireChatMediaBucket(env);
    const objectKey = `avatars/${identity.claims.sub}.bin`;
    await bucket.delete(objectKey);
    return json(200, { ok: true, message: 'Foto profil berhasil dihapus permanen.' }, corsHeaders);
  }

  if (url.pathname === '/v1/session/sync' && request.method === 'POST') {
    const membership = await upsertMembership(env, identity.claims.sub);
    return json(200, { ok: true, membership }, corsHeaders);
  }

  if (url.pathname === '/v1/media/upload' && request.method === 'POST') {
    const uploaded = await uploadMedia(request, env, identity);
    return json(200, { ok: true, ...uploaded }, corsHeaders);
  }

  if (url.pathname.startsWith('/v1/media/') && request.method === 'GET') {
    const key = decodeURIComponent(url.pathname.slice('/v1/media/'.length));
    const response = await readMedia(request, env, identity, key);
    const headers = new Headers(response.headers);
    Object.entries(corsHeaders).forEach(([name, value]) => headers.set(name, value));
    return new Response(response.body, { status: response.status, headers });
  }

  if (url.pathname.startsWith('/v1/media/') && request.method === 'DELETE') {
    const key = decodeURIComponent(url.pathname.slice('/v1/media/'.length));
    const deleted = await deleteMedia(env, identity, key);
    return json(200, { ok: true, deleted }, corsHeaders);
  }

  if (url.pathname === '/v1/push' && request.method === 'POST') {
    const result = await push(request, env, identity);
    return json(200, { ok: true, ...result }, corsHeaders);
  }

  return json(404, { ok: false, code: 'not-found', message: 'Endpoint Chat tidak ditemukan.' }, corsHeaders);
}

async function cleanupTerminalCallSessions(env, now = Date.now()) {
  try {
    const thresholdMillis = now - (24 * 60 * 60 * 1000); // 24 jam retention window
    const docs = await listFirestoreDocumentsPaginated(env, 'callSessions', 100);

    for (const docItem of docs) {
      const name = docItem.name; // projects/.../databases/(default)/documents/callSessions/{callId}
      const callId = name?.split('/').pop();
      if (!callId) continue;

      const state = firestoreValue(docItem.fields?.state);
      const isTerminalState = ['ended', 'rejected', 'missed', 'failed'].includes(state);

      const parseTs = (val) => {
        if (!val) return 0;
        if (typeof val === 'number') return isNaN(val) ? 0 : val;
        if (typeof val === 'string') {
          const num = Number(val);
          if (!isNaN(num) && num > 100000000000) return num;
          const parsed = new Date(val).getTime();
          return isNaN(parsed) ? 0 : parsed;
        }
        if (val.timestampValue) {
          const parsed = new Date(val.timestampValue).getTime();
          return isNaN(parsed) ? 0 : parsed;
        }
        if (val.integerValue) return Number(val.integerValue);
        if (val.doubleValue) return Number(val.doubleValue);
        return 0;
      };

      const updatedAt = parseTs(docItem.fields?.updatedAt);
      const endedAt = parseTs(docItem.fields?.endedAt);
      const docTime = docItem.updateTime ? parseTs(docItem.updateTime) : 0;
      const terminalTime = Math.max(updatedAt, endedAt);

      const expiresAt = Number(
        docItem.fields?.expiresAt?.integerValue ||
        docItem.fields?.expiresAt?.doubleValue ||
        (typeof docItem.fields?.expiresAt === 'number' ? docItem.fields.expiresAt : 0) ||
        0
      );

      const expiredPastThreshold = (expiresAt > 0 && expiresAt < thresholdMillis);
      const terminalAndOldEnough = (isTerminalState && terminalTime > 0 && terminalTime < thresholdMillis);
      const fallbackTerminalOld = (isTerminalState && terminalTime === 0 && docTime > 0 && docTime < thresholdMillis);

      const shouldCleanup = terminalAndOldEnough || expiredPastThreshold || fallbackTerminalOld;

      if (shouldCleanup) {
        // Hapus subkoleksi candidates secara tuntas dengan pagination
        try {
          const candDocs = await listFirestoreDocumentsPaginated(env, `callSessions/${encodeURIComponent(callId)}/candidates`, 100);
          for (const candDoc of candDocs) {
            const candId = candDoc.name?.split('/').pop();
            if (candId) {
              await firestoreRequest(env, `callSessions/${encodeURIComponent(callId)}/candidates/${encodeURIComponent(candId)}`, { method: 'DELETE' }).catch(() => {});
            }
          }
        } catch (_) {}

        // Hapus dokumen sesi panggilan
        await firestoreRequest(env, `callSessions/${encodeURIComponent(callId)}`, { method: 'DELETE' }).catch(() => {});
      }
    }
  } catch (err) {
    console.warn('[CHATROK Worker] cleanupTerminalCallSessions error:', err.message);
  }
}

export default {
  async fetch(request, env) {
    try {
      return await route(request, env);
    } catch (error) {
      console.error('[CHATROK Worker]', error);
      const code = error?.code || 'chat/edge-error';
      const status = (code === 'chat/unauthenticated' || /unauthenticated|Authorization|token/i.test(String(error?.message || '')))
        ? 401
        : (code === 'chat/access-denied' || /access-denied|partisipan|akses|role|hanya pengirim|bukan caller|bukan callee/i.test(String(error?.message || '')))
          ? 403
          : (code === 'chat/origin-denied')
            ? 403
            : 400;
      let corsHeaders = {};
      try {
        corsHeaders = cors(request, env);
      } catch (_) {
        corsHeaders = { vary: 'Origin' };
      }
      return json(status, { ok: false, code, message: String(error?.message || error) }, corsHeaders);
    }
  },
  async scheduled(controller, env, ctx) {
    const scheduledTime = Number(controller.scheduledTime || Date.now());
    ctx.waitUntil(Promise.allSettled([
      cleanupExpiredChatMedia(env, scheduledTime),
      cleanupTerminalCallSessions(env, scheduledTime)
    ]));
  }
};

export {
  listFirestoreDocumentsPaginated,
  listPushTokens,
  cleanupTerminalCallSessions
};
