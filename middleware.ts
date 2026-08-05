import { next } from '@vercel/functions';

declare const process: { env: Record<string, string | undefined> };

export const config = { matcher: '/:path*' };

function cookie(request: Request, name: string): string | null {
  const found = (request.headers.get('cookie') ?? '').split(';')
    .map((part) => part.trim()).find((part) => part.startsWith(`${name}=`));
  return found ? found.slice(name.length + 1) : null;
}

async function validSession(token: string | null, secret: string): Promise<string | null> {
  if (!token) return null;
  const [version, userId, expires, sessionId, signature] = token.split('.');
  if (version !== 'v1' || !userId || !expires || !sessionId || !signature
      || Number(expires) < Math.floor(Date.now() / 1000)) return null;
  const payload = `${version}.${userId}.${expires}.${sessionId}`;
  const key = await crypto.subtle.importKey(
    'raw', new TextEncoder().encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['verify']);
  const base64 = signature.replace(/-/g, '+').replace(/_/g, '/');
  const padded = base64.padEnd(Math.ceil(base64.length / 4) * 4, '=');
  const bytes = Uint8Array.from(atob(padded), (c) => c.charCodeAt(0));
  return (await crypto.subtle.verify(
    'HMAC', key, bytes, new TextEncoder().encode(payload))) ? userId : null;
}

export default async function middleware(request: Request) {
  const url = new URL(request.url);
  const secret = process.env.FINTO_SESSION_SECRET;
  if (!secret) {
    return new Response('Finto authentication is not configured.', { status: 503 });
  }

  const publicPath = url.pathname === '/login'
    || url.pathname === '/api/auth/login'
    || url.pathname === '/api/auth/logout'
    || url.pathname.startsWith('/api/agent/')
    || /\.(?:js|css|ico|svg|png|webp|woff2?|webmanifest)$/.test(url.pathname);
  if (publicPath) return next({ headers: { 'X-Robots-Tag': 'noindex, nofollow' } });

  const userId = await validSession(cookie(request, 'finto_session'), secret);
  if (!userId) {
    if (url.pathname.startsWith('/api/')) {
      return new Response(JSON.stringify({ detail: 'authentication required' }), {
        status: 401,
        headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' },
      });
    }
    const login = new URL('/login', request.url);
    login.searchParams.set('next', `${url.pathname}${url.search}`);
    return Response.redirect(login, 302);
  }

  const requestHeaders = new Headers(request.headers);
  requestHeaders.set('X-Finto-User', userId);
  return next({
    request: { headers: requestHeaders },
    headers: { 'X-Robots-Tag': 'noindex, nofollow' },
  });
}
