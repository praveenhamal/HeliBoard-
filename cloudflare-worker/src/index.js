// Cloudflare Worker for SleekType E2E Encrypted Sync
// Uses native WebCrypto for RS256 Google JWT verification without external dependencies

const MAX_BLOB_SIZE = 5 * 1024 * 1024; // 5 MB

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    // Optional CORS headers if needed for testing, though native app requests don't strictly require CORS
    const corsHeaders = {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, PUT, DELETE, OPTIONS',
      'Access-Control-Allow-Headers': 'Authorization, Content-Type',
    };

    if (request.method === 'OPTIONS') {
      return new Response(null, { headers: corsHeaders });
    }

    // 1. Verify Authorization Header
    const authHeader = request.headers.get('Authorization');
    if (!authHeader || !authHeader.startsWith('Bearer ')) {
      return jsonResponse({ error: 'Missing or malformed Authorization header' }, 401, corsHeaders);
    }

    const idToken = authHeader.substring(7).trim();
    let sub = null;
    try {
      sub = await verifyGoogleIdToken(idToken, env.GOOGLE_CLIENT_ID);
    } catch (err) {
      return jsonResponse({ error: 'Invalid Google token: ' + err.message }, 401, corsHeaders);
    }

    const accountKey = `u:${sub}`;

    // 2. Route Requests
    try {
      if (url.pathname === '/sync' && request.method === 'GET') {
        // Retrieve encrypted sync blob
        const { value, metadata } = await env.SYNC.getWithMetadata(accountKey, { type: 'arrayBuffer' });
        if (!value) {
          return jsonResponse({ error: 'No sync data found' }, 404, corsHeaders);
        }
        return new Response(value, {
          headers: {
            ...corsHeaders,
            'Content-Type': 'application/octet-stream',
            'X-Updated-At': (metadata && metadata.updatedAt) ? metadata.updatedAt.toString() : '0',
          },
        });
      }

      if (url.pathname === '/sync/meta' && request.method === 'GET') {
        // Retrieve metadata (timestamp, size) without downloading the full blob
        const { metadata } = await env.SYNC.getWithMetadata(accountKey, { type: 'arrayBuffer', cacheTtl: 60 });
        if (!metadata) {
          return jsonResponse({ error: 'No sync data found' }, 404, corsHeaders);
        }
        return jsonResponse(metadata, 200, corsHeaders);
      }

      if (url.pathname === '/sync' && request.method === 'PUT') {
        // Store encrypted blob
        const contentLength = parseInt(request.headers.get('Content-Length') || '0', 10);
        if (contentLength > MAX_BLOB_SIZE) {
          return jsonResponse({ error: 'Payload exceeds 5MB limit' }, 413, corsHeaders);
        }

        const body = await request.arrayBuffer();
        if (body.byteLength > MAX_BLOB_SIZE) {
          return jsonResponse({ error: 'Payload exceeds 5MB limit' }, 413, corsHeaders);
        }
        if (body.byteLength === 0) {
          return jsonResponse({ error: 'Empty payload' }, 400, corsHeaders);
        }

        const now = Date.now();
        const metadata = {
          updatedAt: now,
          size: body.byteLength,
        };

        await env.SYNC.put(accountKey, body, { metadata });
        return jsonResponse({ success: true, updatedAt: now, size: body.byteLength }, 200, corsHeaders);
      }

      if (url.pathname === '/sync' && request.method === 'DELETE') {
        // Delete cloud backup
        await env.SYNC.delete(accountKey);
        return jsonResponse({ success: true }, 200, corsHeaders);
      }

      return jsonResponse({ error: 'Not found' }, 404, corsHeaders);
    } catch (e) {
      return jsonResponse({ error: 'Internal server error: ' + e.message }, 500, corsHeaders);
    }
  },
};

function jsonResponse(data, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      ...extraHeaders,
      'Content-Type': 'application/json',
    },
  });
}

// Google RS256 JWKS Cache
let cachedKeys = null;
let cacheExpiresAt = 0;

async function getGooglePublicKeys() {
  const now = Date.now();
  if (cachedKeys && now < cacheExpiresAt) {
    return cachedKeys;
  }
  const res = await fetch('https://www.googleapis.com/oauth2/v3/certs', {
    cf: { cacheTtl: 3600 },
  });
  if (!res.ok) {
    throw new Error('Failed to fetch Google public keys');
  }
  const data = await res.json();
  cachedKeys = data.keys;
  cacheExpiresAt = now + 3600 * 1000;
  return cachedKeys;
}

async function verifyGoogleIdToken(token, expectedAudience) {
  const parts = token.split('.');
  if (parts.length !== 3) {
    throw new Error('Invalid JWT format');
  }

  const [headerB64, payloadB64, signatureB64] = parts;
  const header = JSON.parse(base64UrlDecode(headerB64));
  const payload = JSON.parse(base64UrlDecode(payloadB64));

  if (header.alg !== 'RS256') {
    throw new Error(`Unsupported algorithm: ${header.alg}`);
  }

  // 1. Verify claims
  const nowSeconds = Math.floor(Date.now() / 1000);
  if (payload.exp < nowSeconds) {
    throw new Error('Token expired');
  }

  const validIssuers = ['accounts.google.com', 'https://accounts.google.com'];
  if (!validIssuers.includes(payload.iss)) {
    throw new Error(`Invalid issuer: ${payload.iss}`);
  }

  if (expectedAudience && payload.aud !== expectedAudience) {
    throw new Error(`Audience mismatch. Expected: ${expectedAudience}, got: ${payload.aud}`);
  }

  // 2. Verify signature against Google's JWKS
  const keys = await getGooglePublicKeys();
  const jwk = keys.find((k) => k.kid === header.kid);
  if (!jwk) {
    throw new Error('Matching key ID not found in Google JWKS');
  }

  const cryptoKey = await crypto.subtle.importKey(
    'jwk',
    jwk,
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
    false,
    ['verify']
  );

  const encoder = new TextEncoder();
  const dataToVerify = encoder.encode(`${headerB64}.${payloadB64}`);
  const signatureBytes = base64UrlToUint8Array(signatureB64);

  const isValid = await crypto.subtle.verify(
    'RSASSA-PKCS1-v1_5',
    cryptoKey,
    signatureBytes,
    dataToVerify
  );

  if (!isValid) {
    throw new Error('Invalid signature');
  }

  return payload.sub; // Return unique user identifier
}

function base64UrlDecode(str) {
  let base64 = str.replace(/-/g, '+').replace(/_/g, '/');
  while (base64.length % 4) {
    base64 += '=';
  }
  return atob(base64);
}

function base64UrlToUint8Array(str) {
  const binary = base64UrlDecode(str);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}
