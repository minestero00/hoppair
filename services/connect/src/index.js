// Public app association and a fixed redirect only. Pairing credentials live in URL fragments.
export default {
  fetch(request, env) {
    const url = new URL(request.url);
    if (!['GET', 'HEAD'].includes(request.method)) return new Response('Method not allowed', { status: 405, headers: { Allow: 'GET, HEAD' } });
    if (url.pathname === '/.well-known/assetlinks.json') {
      return Response.json([{ relation: ['delegate_permission/common.handle_all_urls'], target: {
        namespace: 'android_app', package_name: env.ANDROID_PACKAGE,
        sha256_cert_fingerprints: [env.ANDROID_CERT_SHA256],
      } }], { headers: { 'Cache-Control': 'public, max-age=3600', 'X-Content-Type-Options': 'nosniff' } });
    }
    if (url.pathname === '/health') return Response.json({ ok: true, service: 'hoppair-connect' });
    if (url.pathname === '/connect/' || url.pathname === '/connect') return Response.redirect(new URL('connect/', env.SITE_URL).href, 302);
    if (url.pathname === '/') return Response.redirect(env.SITE_URL, 302);
    return new Response('Not found', { status: 404 });
  },
};
