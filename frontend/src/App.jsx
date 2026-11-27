import { useEffect, useState, useCallback } from 'react'

const api = async (path, method = 'GET') => {
  const r = await fetch('/api' + path, { method })
  const body = await r.json().catch(() => ({}))
  if (!r.ok) throw new Error(body.error || 'Request failed')
  return body
}
const fmt = (iso) => (iso ? new Date(iso).toLocaleString() : '-')
const short = (h) => (h ? h.slice(0, 12) + '…' : '-')

function Dot({ state }) {
  return <span className={'dot ' + (state === 'Running' ? 'ok' : state === 'Starting' ? 'warn' : 'bad')} />
}
function Apps({ title, data }) {
  return (
    <div className="cluster">
      <h3>{title}</h3>
      <p className="muted">{data?.cluster}</p>
      {['wordpress', 'mysql'].map((a) => (
        <div className="row" key={a}><span>{a === 'mysql' ? 'MySQL' : 'WordPress'}</span>
          <span><Dot state={data?.apps?.[a]} /> {data?.apps?.[a] ?? '…'}</span></div>
      ))}
    </div>
  )
}

export default function App() {
  const [status, setStatus] = useState(null)
  const [baseline, setBaseline] = useState(null)
  const [backups, setBackups] = useState([])
  const [runs, setRuns] = useState([])
  const [busy, setBusy] = useState('')
  const [log, setLog] = useState([])

  const note = (msg, bad) => setLog((l) => [{ t: new Date().toLocaleTimeString(), msg, bad }, ...l].slice(0, 40))

  const refresh = useCallback(async () => {
    try {
      const [s, b, bk, r] = await Promise.all([api('/status'), api('/baseline'), api('/backups'), api('/runs')])
      setStatus(s); setBaseline(b); setBackups(bk); setRuns(r)
    } catch (e) { note('Backend unreachable: ' + e.message, true) }
  }, [])

  useEffect(() => { refresh(); const id = setInterval(refresh, 8000); return () => clearInterval(id) }, [refresh])

  const act = (key, label, fn) => async () => {
    setBusy(key); note(label + ' started')
    try { const res = await fn(); note(label + ' finished' + (res?.result ? ': ' + res.result : '')) }
    catch (e) { note(label + ' failed: ' + e.message, true) }
    setBusy(''); refresh()
  }

  const latest = runs[0]
  const lastPass = runs.find((r) => r.result === 'PASS')
  const maxSec = Math.max(1, ...runs.map((r) => r.totalSec))
  const check = (ok) => (ok === undefined ? '-' : ok ? 'Pass' : 'Fail')

  return (
    <div className="page">
      <header>
        <h1>Recovery console</h1>
        <p>Back up WordPress and MySQL from one Kubernetes cluster, restore into another, and check the result against a healthy baseline.</p>
      </header>

      <section className="pipeline">
        <div className="stage"><b>Source</b><span>{status?.source?.cluster ?? '…'}</span></div>
        <div className="arrow">Velero backup</div>
        <div className="stage"><b>MinIO</b><span>{backups.length} backups</span></div>
        <div className="arrow">Restore</div>
        <div className="stage"><b>Target</b><span>{status?.target?.cluster ?? '…'}</span></div>
        <div className="arrow">Verify</div>
        <div className={'stage verdict ' + (latest ? (latest.result === 'PASS' ? 'pass' : 'fail') : '')}>
          <b>{latest ? latest.result : 'Not run'}</b><span>latest restore</span></div>
      </section>

      <section>
        <h2>Application health</h2>
        <div className="two"><Apps title="Source" data={status?.source} /><Apps title="Target" data={status?.target} /></div>
      </section>

      <section>
        <h2>Healthy baseline</h2>
        <div className="grid">
          <div><span className="muted">Database records</span><strong>{baseline?.rowCount ?? '-'}</strong></div>
          <div><span className="muted">Data fingerprint</span><strong className="hash" title={baseline?.dataFingerprint}>{short(baseline?.dataFingerprint)}</strong></div>
          <div><span className="muted">Configuration fingerprint</span><strong className="hash" title={baseline?.configFingerprint}>{short(baseline?.configFingerprint)}</strong></div>
          <div><span className="muted">Captured</span><strong>{fmt(baseline?.capturedAt)}</strong></div>
        </div>
        <button disabled={!!busy} onClick={act('base', 'Baseline capture', () => api('/baseline', 'POST'))}>
          {busy === 'base' ? 'Capturing…' : 'Capture healthy baseline'}</button>
      </section>

      <section>
        <h2>Backups</h2>
        <button disabled={!!busy} onClick={act('bk', 'Backup', () => api('/backups', 'POST'))}>
          {busy === 'bk' ? 'Backing up…' : 'Create backup'}</button>
        <div className="table-wrap"><table>
          <thead><tr><th>Name</th><th>Status</th><th>Items</th><th>Created</th><th>Expires</th></tr></thead>
          <tbody>{backups.length === 0 && <tr><td colSpan="5" className="muted">No backups yet. Create one after capturing a baseline.</td></tr>}
            {backups.map((b) => (<tr key={b.name}><td>{b.name}</td><td>{b.phase}</td><td>{b.items} / {b.total}</td><td>{fmt(b.created)}</td><td>{fmt(b.expires)}</td></tr>))}
          </tbody></table></div>
      </section>

      <section>
        <h2>Simulate failure</h2>
        <p>Scales the source WordPress and MySQL deployments to zero. The database volume is kept.</p>
        <div className="btns">
          <button className="danger" disabled={!!busy} onClick={act('fail', 'Failure simulation', () => api('/failure', 'POST'))}>Simulate failure</button>
          <button className="ghost" disabled={!!busy} onClick={act('src', 'Source recovery', () => api('/recover-source', 'POST'))}>Bring source back</button>
        </div>
      </section>

      <section>
        <h2>Restore to target cluster</h2>
        <p>Restores the newest completed backup into {status?.target?.cluster ?? 'the target cluster'}, then compares it with the baseline.</p>
        <button disabled={!!busy} onClick={act('rs', 'Restore', () => api('/restore', 'POST'))}>
          {busy === 'rs' ? 'Restoring and verifying…' : 'Restore application'}</button>
        {latest && (
          <div className="grid verify">
            <div><span className="muted">Functionality</span><strong className={latest.functionOk ? 'okt' : 'badt'}>{check(latest.functionOk)}</strong></div>
            <div><span className="muted">Data</span><strong className={latest.dataOk ? 'okt' : 'badt'}>{check(latest.dataOk)}</strong></div>
            <div><span className="muted">Configuration</span><strong className={latest.configOk ? 'okt' : 'badt'}>{check(latest.configOk)}</strong></div>
            <div><span className="muted">Detail</span><strong>{latest.detail}</strong></div>
          </div>)}
      </section>

      <section>
        <h2>Recovery metrics</h2>
        <div className="grid">
          <div><span className="muted">Total runs</span><strong>{runs.length}</strong></div>
          <div><span className="muted">Successful runs</span><strong>{runs.filter((r) => r.result === 'PASS').length}</strong></div>
          <div><span className="muted">Latest duration</span><strong>{latest ? latest.totalSec.toFixed(1) + 's' : '-'}</strong></div>
          <div><span className="muted">Last successful restore</span><strong>{lastPass?.restoreName ?? '-'}</strong></div>
        </div>
        <div className="bars">{runs.slice(0, 12).reverse().map((r) => (
          <div key={r.id} className={'bar ' + (r.result === 'PASS' ? 'pass' : 'fail')} style={{ height: 8 + (r.totalSec / maxSec) * 92 + '%' }} title={`${r.restoreName}: ${r.totalSec.toFixed(1)}s ${r.result}`} />))}
        </div>
        <div className="table-wrap"><table>
          <thead><tr><th>Restore</th><th>Backup used</th><th>Result</th><th>Total</th><th>Restore</th><th>Verify</th></tr></thead>
          <tbody>{runs.map((r) => (<tr key={r.id}><td>{r.restoreName}</td><td>{r.backupName}</td>
            <td className={r.result === 'PASS' ? 'okt' : 'badt'}>{r.result}</td>
            <td>{r.totalSec.toFixed(1)}s</td><td>{r.restoreSec.toFixed(1)}s</td><td>{r.verifySec.toFixed(1)}s</td></tr>))}</tbody></table></div>
      </section>

      <section>
        <h2>Activity</h2>
        <ul className="log">{log.length === 0 && <li className="muted">Actions you run appear here.</li>}
          {log.map((l, i) => <li key={i} className={l.bad ? 'badt' : ''}><span className="muted">{l.t}</span> {l.msg}</li>)}</ul>
      </section>

      <section>
        <h2>Final assessment</h2>
        <p className={'final ' + (lastPass ? 'okt' : 'muted')}>
          {lastPass ? `Last verified recovery: ${lastPass.restoreName}. A later failed attempt does not change that earlier result.` : 'No successful recovery recorded yet.'}</p>
      </section>
    </div>
  )
}
