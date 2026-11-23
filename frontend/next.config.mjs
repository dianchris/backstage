const backend = process.env.BACKSTAGE_BACKEND_URL || 'http://localhost:8080';
export default {
  poweredByHeader: false,
  async rewrites() {
    return [{ source: '/api/:path*', destination: backend + '/api/:path*' }];
  }
};
