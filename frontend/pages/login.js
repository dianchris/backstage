import Head from 'next/head';
import { useEffect, useState } from 'react';

const accounts = [
  ['maker', 'Maker', 'Membuat draf, mengajukan partner, dan memverifikasi aplikasi.'],
  ['checker', 'Checker', 'Menyetujui, meminta revisi, atau menolak pengajuan.'],
  ['product-manager', 'Product Manager', 'Melihat seluruh pengajuan, detail, dan riwayat.'],
];

export default function Login() {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  useEffect(() => {
    fetch('/api/auth/me').then(response => {
      if (response.ok) window.location.replace('/');
    });
  }, []);

  async function submit(event) {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      const response = await fetch('/api/auth/login', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password }),
      });
      const result = await response.json();
      if (!response.ok) throw new Error(result.error || 'Gagal masuk.');
      window.location.replace('/');
    } catch (error) {
      setError(error.message);
      setBusy(false);
    }
  }

  return <>
    <Head><title>Masuk · API Backstage</title></Head>
    <main className="login-page">
      <section className="login-intro">
        <div className="brand"><span className="brand-mark" aria-hidden="true">S</span>
          <span className="brand-name"><small>bank</small><strong>sinarmas</strong></span>
          <span className="brand-product">API<br />Backstage</span>
        </div>
        <h1>Kelola partner<br />dari satu workspace.</h1>
        <p>Onboarding partner, review aplikasi, dan pengelolaan API untuk tim internal.</p>
      </section>
      <section className="card login-card">
        <h2>Masuk ke Backstage</h2>
        <p>Gunakan akun sampel untuk mencoba workspace.</p>
        <form onSubmit={submit}>
          <label className="field">Username<input autoComplete="username" required value={username}
            onChange={event => setUsername(event.target.value)} /></label>
          <label className="field">Password<input type="password" autoComplete="current-password" required value={password}
            onChange={event => setPassword(event.target.value)} /></label>
          {error && <p className="error" role="alert">{error}</p>}
          <button className="button primary login-submit" disabled={busy}>{busy ? 'Memproses…' : 'Masuk'}</button>
        </form>
        <div className="sample-accounts">
          <h3>Akun sampel</h3>
          <p>Password semua akun <code>Backstage123!</code></p>
          {accounts.map(([id, title, description]) => <button key={id} disabled={busy}
            className="sample-account" type="button" onClick={() => { setUsername(id); setPassword('Backstage123!'); setError(''); }}>
            <strong>{title}<code>{id}</code></strong><span>{description}</span>
          </button>)}
        </div>
      </section>
    </main>
  </>;
}
