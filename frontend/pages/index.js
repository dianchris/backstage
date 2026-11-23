import Head from 'next/head';
import { useEffect, useState } from 'react';

const states = {
  draft: 'Draf', verification: 'Menunggu verifikasi', review: 'Dalam Review',
  revision: 'Butuh Revisi', approved: 'Disetujui', rejected: 'Ditolak',
  active: 'Aktif', scheduled: 'Terjadwal', executed: 'Selesai',
  failed: 'Gagal diproses', sent: 'Email terkirim', completed: 'Selesai', expired: 'Kedaluwarsa',
};
const filters = [['', 'Semua'], ['review', 'Dalam Review'], ['revision', 'Butuh Revisi'],
  ['approved', 'Disetujui'], ['rejected', 'Ditolak'], ['draft', 'Draf']];
const company = [
  ['name', 'Nama perusahaan', 'Masukkan nama perusahaan'],
  ['industry', 'Industri', 'Masukkan jenis industri'],
  ['companyEmail', 'Email perusahaan', 'email@perusahaan.com', 'email'],
  ['website', 'URL website (opsional)', 'https://www.perusahaan.com', 'url'],
  ['companyPhone', 'Nomor telepon', 'Contoh: 021 50188888', 'tel'],
];
const contact = prefix => [
  [prefix + 'Name', 'Nama', 'Masukkan nama'],
  [prefix + 'Email', 'Email', 'email@perusahaan.com', 'email'],
  [prefix + 'Phone', 'Nomor telepon', 'Contoh: 08178293800', 'tel'],
];
const invitations = [
  ['makerEmail', 'Email Partner Maker', 'maker@perusahaan.com', 'email'],
  ['checkerEmail', 'Email Partner Checker', 'checker@perusahaan.com', 'email'],
];
const stepNames = ['Detail perusahaan', 'Project manager & tim support'];

const historyLabels = {
  draft: 'Draf disimpan', review: 'Pengajuan dikirim ke Checker',
  approved: 'Pendaftaran disetujui', active: 'Partner aktif',
  rejected: 'Pendaftaran ditolak', revision: 'Revisi diminta',
  invitation_queued: 'Onboarding menunggu proses',
  invitation_sent: 'Undangan API Portal terkirim',
  invitation_failed: 'Pengiriman undangan gagal',
  portal_registration_succeeded: 'Project manager terdaftar di API Portal',
  password_reset_requested: 'Reset password menunggu persetujuan',
  password_reset_approved: 'Reset password disetujui',
  password_reset_rejected: 'Reset password ditolak',
  password_reset_issued: 'Tautan reset password dibuat',
  password_reset_queued: 'Reset password menunggu proses',
  password_reset_sent: 'Email reset password terkirim',
  password_reset_failed: 'Proses reset password gagal',
  password_reset_completed: 'Password API Portal diperbarui',
  password_reset_expired: 'Tautan reset password kedaluwarsa',
  portal_login_succeeded: 'Project manager berhasil login ke API Portal',
  portal_registration_accepted: 'Akun API Portal telah diaktifkan',
  portal_registration_expired: 'Undangan API Portal kedaluwarsa',
  portal_registration_failed: 'Registrasi API Portal gagal',
};
const actorLabels = { maker: 'Backstage Maker', checker: 'Backstage Checker', email: 'Layanan onboarding', portal: 'API Portal' };

function formatDate(value, includeTime = false) {
  return value ? new Date(value).toLocaleString('id-ID', {
    dateStyle: 'medium', ...(includeTime && { timeStyle: 'short' }),
  }) : '—';
}

function DetailFields({ items }) {
  return <dl className="registration-fields">
    {items.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value || '—'}</dd></div>)}
  </dl>;
}

function PartnerRegistration({ record, onBack, onRetry, busy, canRetry, children }) {
  const details = record.details ?? {};
  const history = [...record.history].sort((left, right) => Date.parse(right.at) - Date.parse(left.at));
  const failure = history.find(entry => ['invitation_failed', 'portal_registration_failed'].includes(entry.state));
  const retryLabel = record.invitationDelivery?.failureStep === 'registration'
    ? 'Coba Registrasi Ulang' : 'Coba Kirim Ulang';
  const submitted = record.history.find(entry => entry.state === 'review');
  const contactFields = prefix => contact(prefix).map(([key, label]) => [label, details[key]]);
  return <>
    <nav className="breadcrumb" aria-label="Lokasi halaman">
      <button onClick={onBack}>Partner</button><span aria-hidden="true">›</span><span>Detail Pendaftaran</span>
    </nav>
    <h1>Detail Pendaftaran</h1>
    <div className="registration-layout">
      <div className="registration-content">
        <section className="card registration-summary" aria-label="Ringkasan pengajuan">
          <span className={'status ' + record.state}>{states[record.state] ?? record.state}</span>
          <DetailFields items={[
            ['ID pengajuan', record.id], ['Tanggal pengajuan', formatDate(submitted?.at)],
            ['Diajukan oleh', actorLabels[submitted?.actor] ?? submitted?.actor],
            ['Jenis pengajuan', 'Penambahan partner baru'],
          ]} />
        </section>
        <section className="card">
          <div className="company-heading">
            <span className="company-icon" aria-hidden="true">▦</span>
            <div><h2>{record.name}</h2>{details.industry && <p>{details.industry}</p>}</div>
          </div>
          <h3>Detail perusahaan</h3>
          <DetailFields items={company.map(([key, label]) => [label, key === 'name' ? record.name : details[key]])} />
        </section>
        <details open className="card registration-section">
          <summary>Project manager &amp; tim support</summary>
          <h3>Detail project manager</h3>
          <DetailFields items={contactFields('manager')} />
          <h3>Detail tim support</h3>
          <DetailFields items={contactFields('support')} />
        </details>
        {children}
      </div>
      <aside className="card onboarding-history">
        <h2>Riwayat onboarding</h2>
        <ol>
          {history.map((entry, index) =>
            <li key={index} className={entry.state}>
              <strong>{historyLabels[entry.state] ?? states[entry.state] ?? entry.state}</strong>
              {entry.note && <p>{entry.note}</p>}
              <span>{actorLabels[entry.actor] ?? entry.actor}</span>
              <time dateTime={entry.at}>{formatDate(entry.at, true)}</time>
              {entry === failure && record.invitationDelivery?.status === 'failed' && canRetry &&
                <button className="button secondary history-retry" disabled={busy} onClick={onRetry}>
                  {busy ? 'Memproses…' : retryLabel}
                </button>}
            </li>)}
        </ol>
        {record.invitationDelivery?.status === 'queued' &&
          <p className="delivery-pending" role="status">Memproses registrasi dan pengiriman undangan…</p>}
      </aside>
    </div>
  </>;
}

export default function Backstage() {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(true);
  const role = user?.role;
  const [records, setRecords] = useState([]);
  const [view, setView] = useState('dashboard');
  const [selected, setSelected] = useState(null);
  const [page, setPage] = useState(1);
  const [filter, setFilter] = useState('');
  const [query, setQuery] = useState('');
  const [editor, setEditor] = useState(null);
  const [step, setStep] = useState(0);
  const [note, setNote] = useState('');
  const [deliveries, setDeliveries] = useState([]);
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const [due, setDue] = useState('');
  const [resetReason, setResetReason] = useState('');

  async function api(path, method = 'GET', body) {
    const response = await fetch(path, {
      method,
      headers: { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const data = await response.json();
    if (response.status === 401) {
      setUser(null);
      window.location.replace('/login');
    }
    if (!response.ok) throw new Error(data.error || 'Permintaan gagal');
    return data;
  }

  useEffect(() => {
    let active = true;
    api('/api/auth/me').then(async account => {
      const data = await api('/api/requests');
      if (active) { setUser(account); setRecords(data); setLoading(false); }
    }).catch(error => { if (active) setMessage(error.message); });
    return () => { active = false; };
  }, []);

  useEffect(() => {
    if (!user || !records.some(item => item.resetDelivery?.status === 'queued' || item.kind === 'password-reset' && ['sent', 'failed'].includes(item.state) || item.invitationDelivery?.status === 'queued' || item.portalRegistration?.status === 'registered' || (item.id === selected && item.kind === 'partner'))) return;
    let active = true;
    const timer = setInterval(() => {
      api('/api/requests').then(data => { if (active) setRecords(data); })
        .catch(error => { if (active) setMessage(error.message); });
    }, 3000);
    return () => { active = false; clearInterval(timer); };
  }, [user, records, selected]);

  async function run(action) {
    if (busy) return;
    setBusy(true);
    setMessage('');
    try {
      await action();
      setRecords(await api('/api/requests'));
    } catch (error) {
      setMessage(error.message);
    } finally {
      setBusy(false);
    }
  }

  function navigate(next) {
    setView(next);
    setResetReason('');
    setPage(1);
    setEditor(null);
    setSelected(null);
    setFilter('');
    setQuery('');
    setDeliveries([]);
    setMessage('');
  }

  function openEditor(record) {
    setMessage('');
    setStep(0);
    setEditor({
      ...record?.details, id: record?.id, name: record?.name,
      makerEmail: record?.emails?.[0], checkerEmail: record?.emails?.[1],
    });
  }

  function patch(key, value) {
    setEditor(previous => ({ ...previous, [key]: value }));
  }

  function fields(definitions, { required = true, fromManager = false } = {}) {
    return definitions.map(([key, label, placeholder, type = 'text']) => (
      <label className="field" key={key}>
        {label}
        <input type={type} placeholder={placeholder} maxLength={254}
          required={required && key !== 'website'} disabled={fromManager}
          value={editor[fromManager ? key.replace(/^support/, 'manager') : key] ?? ''}
          onChange={event => patch(key, event.target.value)} />
      </label>
    ));
  }

  function save(submit) {
    const { id, name, makerEmail, checkerEmail, ...details } = editor;
    if (!name?.trim()) {
      setStep(0);
      setMessage('Isi nama perusahaan untuk menyimpan draf.');
      return;
    }
    run(async () => {
      const saved = await api(id ? '/api/partners/' + id : '/api/partners', id ? 'PATCH' : 'POST', {
        name, makerEmail, checkerEmail, details,
      });
      // Retain the ID if submission fails after the draft is saved.
      setEditor(previous => ({ ...previous, id: saved.id }));
      if (submit) await requestAction(saved, 'submit');
      setEditor(null);
      setFilter('');
      setQuery('');
      setSelected(null);
    });
  }

  async function requestAction(record, action) {
    return api((record.kind === 'password-reset' ? '/api/password-resets/' : '/api/requests/') + record.id + '/actions', 'POST', {
      action, note, version: record.history.length,
    });
  }

  function act(record, action) {
    run(async () => {
      const result = await requestAction(record, action);
      setDeliveries(result.invitations ?? []);
      setNote('');
      if (action === 'delete') setSelected(null);
    });
  }

  function selectRecord(item) {
    if (item.kind === 'partner' && item.state === 'draft') {
      if (role === 'maker') { openEditor(item); return; }
    }
    setSelected(item.id);
    setNote('');
    setDue('');
    setDeliveries([]);
    setMessage('');
    if (item.kind === 'partner') window.scrollTo(0, 0);
  }

  function closePartner() {
    setSelected(null);
    setDeliveries([]);
    setMessage('');
  }

  function rotate(record, mode) {
    run(async () => {
      if (mode === 'scheduled' && !due) throw new Error('Pilih waktu rotasi.');
      const result = await api('/api/applications/' + record.id + '/rotations', 'POST', {
        mode, executeAt: mode === 'scheduled' ? new Date(due).toISOString() : undefined,
      });
      setSelected(result.id);
    });
  }

  const record = records.find(item => item.id === selected);
  const partnerDetail = record?.kind === 'partner';
  const isTask = item => role === 'maker'
    ? (item.kind === 'partner' && ['draft', 'revision'].includes(item.state)) || item.state === 'verification' || (item.kind === 'password-reset' && item.state === 'failed')
    : role === 'checker' && item.state === 'review';
  const taskCount = records.filter(isTask).length;
  const taskView = view === 'dashboard' && role !== 'product-manager';
  const rows = records.filter(item =>
    (view === 'partner' ? item.kind === 'partner' : !taskView || isTask(item)) &&
    (view === 'dashboard' || !filter || item.state === filter) &&
    [item.name, item.id, item.kind, item.details?.industry, item.description]
      .filter(Boolean).join(' ').toLowerCase().includes(query.trim().toLowerCase()));
  const pageSize = 10;
  const pageCount = Math.max(1, Math.ceil(rows.length / pageSize));
  const currentPage = Math.min(page, pageCount);
  const dashboardRows = rows.slice((currentPage - 1) * pageSize, currentPage * pageSize);
  const requestTypes = { partner: 'Pendaftaran partner', application: 'Pengajuan aplikasi', rotation: 'Rotasi API key', 'password-reset': 'Reset password API Portal' };
  const taskAction = item => item.state === 'draft' ? 'Lanjutkan draf'
    : item.state === 'revision' ? 'Perbaiki pengajuan'
    : item.state === 'verification' ? 'Verifikasi pengajuan' : item.state === 'failed' ? 'Coba proses ulang' : 'Review pengajuan';

  const checkerActions = record && role === 'checker' && record.state === 'review' && <>
    <input aria-label="Catatan review" placeholder="Catatan revisi atau penolakan" maxLength={200}
      value={note} onChange={event => setNote(event.target.value)} />
    <button className="button outline" disabled={busy || !note.trim()} onClick={() => act(record, 'reject')}>Tolak</button>
    {record.kind !== 'password-reset' && <button className="button outline" disabled={busy || !note.trim()} onClick={() => act(record, 'revise')}>Minta Revisi</button>}
    <button className="button primary" disabled={busy} onClick={() => act(record, 'approve')}>Setujui</button>
  </>;

  if (loading) return <main><p>Memuat workspace…</p>{message && <p role="alert">{message}</p>}</main>;

  return <>
    <Head><title>API Backstage · {view === 'partner' ? 'Partner' : 'Dashboard'}</title></Head>
    <div className="shell">
      <aside className="sidebar">
        <div className="brand"><span className="brand-mark" aria-hidden="true">S</span>
          <span className="brand-name"><small>bank</small><strong>sinarmas</strong></span>
          <span className="brand-product">API<br />Backstage</span>
        </div>
        <nav aria-label="Navigasi utama">
          <button className={view === 'dashboard' ? 'nav active' : 'nav'} onClick={() => navigate('dashboard')}>
            <span aria-hidden="true">▦</span> Dashboard
          </button>
          <button className={view === 'partner' ? 'nav active' : 'nav'} onClick={() => navigate('partner')}>
            <span aria-hidden="true">♟</span> Partner
          </button>
        </nav>
      </aside>
      <header className="topbar">
        <div><strong>Production Operations</strong><small>Workspace lokal</small></div>
        <div className="identity"><span aria-hidden="true">{user?.name?.[0]}</span>
          <strong>{user?.name}</strong>
          <button className="button outline" disabled={busy} onClick={() => run(async () => {
            await api('/api/auth/logout', 'POST', {});
            window.location.replace('/login');
          })}>Keluar</button>
        </div>
      </header>
      <main>
        {message && <p className="error" role="alert">{message}</p>}
        {editor ? <>
          <div className="breadcrumb"><button onClick={() => navigate('partner')}>Partner</button><span>›</span>Pendaftaran Partner</div>
          <h1>{editor.id ? 'Edit Partner' : 'Pendaftaran Partner'}</h1>
          <section className="card form-card">
            <nav className="steps" aria-label="Langkah pendaftaran">
              {stepNames.map((name, index) => <button key={name} type="button"
                aria-current={step === index ? 'step' : undefined} onClick={() => setStep(index)}>
                <span>{index + 1}</span>{name}
              </button>)}
            </nav>
            <form onSubmit={event => {
              event.preventDefault();
              if (step === 0) setStep(1);
              else save(true);
            }}>
              {step === 0 ? <><h2>Detail perusahaan</h2>{fields(company)}</> : <>
                <h2>Detail project manager</h2>{fields(contact('manager'))}
                <h2>Detail tim support</h2>
                <label className="checkbox"><input type="checkbox" checked={Boolean(editor.sameContact)}
                  onChange={event => patch('sameContact', event.target.checked)} /> Sama dengan project manager</label>
                {fields(contact('support'), { fromManager: editor.sameContact })}
                <h2>Akses Portal</h2>
                {fields(invitations)}
              </>}
              <footer className="form-actions">
                <button type="button" className="button outline" disabled={busy}
                  onClick={() => step === 0 ? setEditor(null) : setStep(0)}>{step === 0 ? 'Batalkan' : '‹ Kembali'}</button>
                <div>
                  {editor.id && records.find(item => item.id === editor.id)?.state === 'draft' &&
                    <button type="button" className="button outline" disabled={busy} onClick={() => {
                      if (!window.confirm('Hapus draf ini?')) return;
                      run(async () => {
                        await requestAction(records.find(item => item.id === editor.id), 'delete');
                        setEditor(null);
                      });
                    }}>Hapus Draf</button>}
                  <button type="button" className="button secondary" disabled={busy} onClick={() => save(false)}>Simpan sebagai Draf</button>
                  <button className="button primary" disabled={busy}>{step === 0 ? 'Lanjutkan ›' : 'Ajukan untuk Persetujuan'}</button>
                </div>
              </footer>
            </form>
          </section>
        </> : partnerDetail ? <PartnerRegistration record={record} onBack={closePartner}
          busy={busy} canRetry={['maker', 'checker'].includes(role)}
          onRetry={() => run(async () => {
            await api('/api/partners/' + record.id + '/invitation/retry', 'POST', {});
          })}>
          {checkerActions && <section className="card registration-actions" aria-label="Keputusan Checker">{checkerActions}</section>}
          {role === 'maker' && record.state === 'revision' && <section className="card registration-actions">
            <button className="button primary" disabled={busy} onClick={() => openEditor(record)}>Edit Partner</button>
          </section>}
          {['approved', 'active'].includes(record.state) && <section className="card">
            <h2>Reset password API Portal</h2>
            <p>Email terdaftar: {record.details?.managerEmail || '—'}</p>
            {role === 'maker' && <form onSubmit={event => {
              event.preventDefault();
              run(async () => {
                const reset = await api('/api/partners/' + record.id + '/password-resets', 'POST', { reason: resetReason });
                setView('dashboard'); setSelected(reset.id); setResetReason(''); setNote('');
              });
            }}>
              <label className="field">Catatan permintaan (opsional)
                <input maxLength={500} value={resetReason} placeholder="Contoh: Pengguna lupa password"
                  onChange={event => setResetReason(event.target.value)} />
              </label>
              <p>Permintaan harus disetujui Checker sebelum tautan reset dikirim.</p>
              <button className="button outline" disabled={busy || !record.details?.managerEmail}>Ajukan Reset Password</button>
            </form>}
            {records.filter(item => item.kind === 'password-reset' && item.organizationId === record.id).map(item =>
              <p key={item.id}><button className="record-link" onClick={() => {
                setView('dashboard'); selectRecord(item);
              }}>Reset password · {states[item.state] ?? item.state} · {formatDate(item.updatedAt, true)}</button></p>)}
          </section>}
          {deliveries.length > 0 && <section className="card deliveries"><h3>Undangan demo</h3>{deliveries.map(invite =>
            <p key={invite.role}>{invite.role} · {invite.email}<br /><code>{invite.token}</code></p>)}</section>}
        </PartnerRegistration> : <>
          <div className="page-heading">
            <div><h1>{view === 'partner' ? 'Partner' : 'Dashboard'}</h1>
              <p>{view === 'partner' ? 'Daftar partner yang sudah Anda ajukan atau proses.' : role === 'product-manager' ? 'Pantau seluruh pengajuan dan perkembangannya.' : 'Kelola tugas dan pantau status pengajuan Anda.'}</p></div>
            {view === 'partner' && role === 'maker' && <button className="button primary" onClick={() => openEditor()}>⊕ Tambah Partner</button>}
          </div>
          <section className={'card queue-card' + (view === 'dashboard' ? ' dashboard-queue' : '')}>
            {view === 'dashboard' && <div className="dashboard-heading">
              <h2>{taskView ? 'My tasks' : 'Semua pengajuan'} <span>({taskView ? taskCount : records.length})</span></h2>
            </div>}
            {view === 'partner' && <div className="tabs" aria-label="Filter status">
              {filters.map(([value, text]) => <button key={text} aria-pressed={filter === value}
                onClick={() => setFilter(value)}>{text}</button>)}
            </div>}
            <div className="search-row">
              <input aria-label="Cari pengajuan" type="search" placeholder="Cari nama partner atau aplikasi"
                value={query} onChange={event => { setQuery(event.target.value); setPage(1); }} />
              <button className="button outline" disabled={busy} onClick={() => run(async () => {})}>Muat Ulang</button>
            </div>
            {view === 'dashboard' && rows.length > 0 ? <ul className="task-list">
              {dashboardRows.map(item => {
                const organization = records.find(partner => partner.id === item.organizationId);
                const lastNote = [...item.history].reverse().find(entry => entry.note)?.note;
                return <li key={item.id}><button className="task-card" onClick={() => selectRecord(item)}
                  aria-label={item.name + ' · ' + (states[item.state] ?? item.state)}>
                  <div className="task-topline">
                    <span className="task-reference">{item.id} <span aria-hidden="true">·</span> {requestTypes[item.kind]}</span>
                    <span className={'status ' + item.state}>{states[item.state] ?? item.state}</span>
                  </div>
                  <strong className="task-title">{item.name}</strong>
                  <p className="task-description">
                    {item.kind === 'partner' ? [item.details?.industry, item.details?.companyEmail].filter(Boolean).join(' · ')
                      : item.kind === 'password-reset' ? [item.email, item.reason].filter(Boolean).join(' · ')
                      : item.kind === 'application' ? item.description
                      : (item.mode === 'scheduled' ? 'Terjadwal · ' + formatDate(item.executeAt, true) : 'Rotasi segera')}
                  </p>
                  {item.state === 'revision' && lastNote && <p className="task-feedback">Catatan revisi: {lastNote}</p>}
                  <div className="task-footer">
                    <span>Terakhir diperbarui: {formatDate(item.updatedAt, true)}</span>
                    {organization && <span>Partner: {organization.name}</span>}
                    {item.environment && <span>Environment: {item.environment}</span>}
                    {taskView && <span className="task-next">{taskAction(item)} <span aria-hidden="true">›</span></span>}
                  </div>
                </button></li>;
              })}
            </ul> : rows.length ? <div className="table-wrap"><table>
              <thead><tr><th>Nama</th><th>Status</th><th>Terakhir diperbarui</th></tr></thead>
              <tbody>{rows.map(item => <tr key={item.id}>
                <td><button className="record-link" onClick={() => selectRecord(item)}>{item.name}</button></td>
                <td><span className={'status ' + item.state}>{states[item.state] ?? item.state}</span></td>
                <td>{new Date(item.updatedAt).toLocaleString('id-ID')}</td>
              </tr>)}</tbody>
            </table></div> : <div className="empty"><span className="empty-icon" aria-hidden="true">⌕</span>
              <strong>{query || filter ? 'Tidak ada hasil' : view === 'partner' ? 'Belum ada partner' : taskView ? 'Tidak ada tugas menunggu' : 'Belum ada pengajuan'}</strong>
              {!query && !filter && view === 'partner' && role === 'maker' && <p>Tambahkan partner baru untuk ditampilkan di sini.</p>}
            </div>}
            {view === 'dashboard' && rows.length > 0 && <nav className="pagination" aria-label="Halaman tugas">
              <span>Menampilkan {(currentPage - 1) * pageSize + 1}–{Math.min(currentPage * pageSize, rows.length)} dari {rows.length}</span>
              <div>
                <button className="button outline" disabled={currentPage === 1}
                  onClick={() => setPage(currentPage - 1)}>‹ Sebelumnya</button>
                <span aria-live="polite">Halaman {currentPage} dari {pageCount}</span>
                <button className="button outline" disabled={currentPage === pageCount}
                  onClick={() => setPage(currentPage + 1)}>Berikutnya ›</button>
              </div>
            </nav>}
          </section>
          {record && <section className="card detail">
            <div className="page-heading"><h2>{record.name}</h2><button className="button outline" onClick={() => { setSelected(null); setDeliveries([]); }}>Tutup</button></div>
            <p>{states[record.state]} · {record.id}</p>
            {record.kind === 'password-reset' && <>
              <p>Email terdaftar: <strong>{record.email}</strong></p>
              {record.reason && <p>Catatan permintaan: {record.reason}</p>}
              <p>Permintaan ini memerlukan persetujuan Checker. Tautan reset dikirim ke email terdaftar.</p>
              {record.resetDelivery?.expiresAt && <p>Berlaku sampai: {formatDate(record.resetDelivery.expiresAt, true)}</p>}
              {record.resetDelivery?.status === 'queued' && <p role="status">Memproses tautan dan pengiriman email…</p>}
            </>}
            {record.description && <p>{record.description}</p>}
            {record.environment && <p>{record.environment} · {record.credentialMethod} · {record.scopes?.join(', ')}</p>}
            {record.kind === 'application' && Object.entries(record.details ?? {}).map(([key, value]) => <p key={key}>{key} · {value}</p>)}
            {record.mode && <p>Rotasi: {record.mode}{record.executeAt && ' · ' + new Date(record.executeAt).toLocaleString('id-ID')}</p>}
            {record.gateway && <p>{record.gateway.route} · Client ID {record.gateway.clientId} · Versi key {record.gateway.keyVersion}</p>}
            <details><summary>Riwayat</summary><ol>{record.history.map((entry, index) =>
              <li key={index}>{states[entry.state] ?? entry.state} · {entry.actor} · {new Date(entry.at).toLocaleString('id-ID')}{entry.note && ' · ' + entry.note}</li>)}</ol></details>
            <div className="detail-actions">
              {role === 'maker' && record.state === 'verification' && <button className="button primary" disabled={busy}
                onClick={() => act(record, 'verify')}>Verifikasi</button>}
              {checkerActions}
              {record.kind === 'password-reset' && record.state === 'failed' && ['maker', 'checker'].includes(role) &&
                <button className="button primary" disabled={busy} onClick={() => run(async () => {
                  await api('/api/password-resets/' + record.id + '/retry', 'POST', {});
                })}>Coba Proses Ulang</button>}
              {role === 'maker' && record.state === 'active' && record.kind === 'application' && <>
                <button className="button primary" disabled={busy} onClick={() => rotate(record, 'immediate')}>Rotasi Sekarang</button>
                <input aria-label="Jadwal rotasi" type="datetime-local" value={due} onChange={event => setDue(event.target.value)} />
                <button className="button outline" disabled={busy || !due} onClick={() => rotate(record, 'scheduled')}>Jadwalkan Rotasi</button>
              </>}
            </div>
          </section>}
        </>}
      </main>
    </div>
  </>;
}
