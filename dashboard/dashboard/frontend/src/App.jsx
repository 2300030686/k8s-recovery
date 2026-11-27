import { useEffect, useRef, useState } from 'react';
const j = (u) => fetch(u).then(r => r.json());
const sec = s => (s == null ? '–' : Number(s).toFixed(1) + 's');
const when = x => (x ? new Date(x).toLocaleString() : '–');
const NAV = [
  ['PLATFORM', [['architecture', 'Architecture'], ['health', 'Health']]],
  ['RECOVERY WORKFLOW', [['baseline', 'Baseline'], ['backup', 'Backup'], ['failure', 'Failure Simulation'], ['restore', 'Cross-Cluster Recovery']]],
  ['ASSESSMENT', [['metrics', 'Recovery Metrics'], ['logs', 'Activity Log'], ['verification', 'Verification'], ['assessment', 'Assessment']]],
];
const IDS = NAV.flatMap(([, l]) => l.map(x => x[0]));
const kind = l => {
  const m = l.msg || '';
  if (l.level === 'ERROR' || /\b(fail(ed)?|error)\b/i.test(m)) return ['✕', 'er'];
  if (l.level === 'OK' || /successfully rolled out|\bCompleted\b|\bPASS\b/.test(m)) return ['✓', 'ok'];
  return ['→', 'in'];
};
const Pill = ({ v }) => <span className={'pill ' + (v === 'PASS' || v === true ? '' : v == null ? 'na' : 'bad')}>{v === true ? 'ONLINE' : v === false ? 'OFFLINE' : v ?? 'N/A'}</span>;
const Sec = ({ id, n, title, badge, children }) => (
  <section id={id} className="panel"><header><h2>{n}. {title}</h2><span className="badge">{badge}</span></header><div className="body">{children}</div></section>);

function Chart({ data }) {
  const W = 1000, H = 250, pad = 50, max = Math.max(1, ...data.map(d => d.total_s || 0));
  const x = i => pad + (data.length < 2 ? (W - 2 * pad) / 2 : i * (W - 2 * pad) / (data.length - 1));
  const y = v => H - 36 - (v / max) * (H - 80);
  const line = (k, c) => <g key={k}><polyline fill="none" stroke={c} strokeWidth="3" points={data.map((d, i) => `${x(i)},${y(d[k] || 0)}`).join(' ')} />
    {data.map((d, i) => <circle key={i} cx={x(i)} cy={y(d[k] || 0)} r="5" fill="#fff" stroke={c} strokeWidth="3" />)}</g>;
  return (<><svg viewBox={`0 0 ${W} ${H}`} className="chart">
    {[0, .5, 1].map(f => <g key={f}><line x1={pad} x2={W - pad} y1={y(max * f)} y2={y(max * f)} stroke="#e2e8f0" /><text x="4" y={y(max * f) + 4} className="ax">{(max * f).toFixed(0)}s</text></g>)}
    {line('total_s', '#2563eb')}{line('restore_s', '#f59e0b')}{line('verify_s', '#14b8a6')}
    {data.map((d, i) => <text key={i} x={x(i)} y={H - 10} textAnchor="middle" className="ax">Run {i + 1}</text>)}</svg>
    <div className="legend"><span><i style={{ background: '#2563eb' }} />Total</span><span><i style={{ background: '#f59e0b' }} />Restore</span><span><i style={{ background: '#14b8a6' }} />Verify</span></div></>);
}

export default function App() {
  const [st, setSt] = useState(null), [recs, setRecs] = useState([]), [runs, setRuns] = useState([]);
  const [logs, setLogs] = useState([]), [note, setNote] = useState(''), [active, setActive] = useState('architecture');
  const last = useRef(0), box = useRef(null);
  const poll = async () => { try { const [a, b, c] = await Promise.all([j('/api/state'), j('/api/recoveries'), j('/api/runs')]); setSt(a); setRecs(b); setRuns(c); } catch { setSt(null); } };
  const pollLogs = async () => { try { const l = await j('/api/logs?after=' + last.current); if (l.length) { last.current = l[l.length - 1].id; setLogs(p => [...p, ...l].slice(-400)); } } catch { /* next tick */ } };
  useEffect(() => { poll(); pollLogs(); const a = setInterval(poll, 5000), b = setInterval(pollLogs, 2000); return () => { clearInterval(a); clearInterval(b); }; }, []);
  useEffect(() => { if (box.current) box.current.scrollTop = box.current.scrollHeight; }, [logs]);
  useEffect(() => {
    const o = new IntersectionObserver(es => es.forEach(e => e.isIntersecting && setActive(e.target.id)), { rootMargin: '-20% 0px -70% 0px' });
    IDS.forEach(i => { const el = document.getElementById(i); if (el) o.observe(el); }); return () => o.disconnect();
  }, []);

  const act = async n => { if (n === 'reset' && !window.confirm('This deletes the restored app in the TARGET cluster and wipes its own data folder. Continue?')) return; const r = await fetch('/api/actions/' + n, { method: 'POST' }); setNote(r.status === 202 ? '' : 'Another action is still running.'); setTimeout(poll, 800); };
  const cancel = async () => { await fetch('/api/cancel', { method: 'POST' }); setTimeout(poll, 800); };
  const busy = st && st.busy;
  const Btn = ({ n, label, kind }) => <button className={kind} disabled={!st || !!busy} onClick={() => act(n)}>{busy === n ? 'Running…' : label}</button>;
  const src = st && st.source, tgt = st && st.target, base = st && st.baseline;
  const backups = (st && st.backups) || [], latestB = backups.find(b => b.phase === 'Completed');
  const latest = recs[0], ok = recs.filter(r => r.result === 'PASS').length, lastPass = recs.find(r => r.result === 'PASS');
  const lastRestore = runs.find(r => r.step === 'restore');
  const restoreState = busy === 'restore' ? 'Running' : lastRestore ? (lastRestore.status === 'OK' ? 'Completed' : 'Failed') : 'Pending';
  const chk = needle => { const l = [...logs].reverse().find(x => x.msg.startsWith(needle)); return l ? (l.msg.includes('PASS') ? 'PASS' : 'FAIL') : null; };
  const CHECKS = [['Row count', 'Restored row count equals the healthy baseline.'], ['Data fingerprint', 'SHA-256 over every row matches the baseline.'], ['Deployments ready', 'mysql, api and ui are ready in the target cluster.']];
  const Node = ({ cls, tag, name, text, rows }) => (<div className={'node ' + cls}><span className="lbl">{tag}</span><h3>{name}</h3><p>{text}</p>{rows.map(([a, b]) => <div className="row" key={a}><span>{a}</span><b>{b}</b></div>)}</div>);

  return (<div className="layout">
    <aside className="side">
      {NAV.map(([g, items]) => <div key={g}><h4>{g}</h4>{items.map(([id, t]) => <a key={id} href={'#' + id} className={active === id ? 'on' : ''}>{t}</a>)}</div>)}
      <div className="live">{!st ? '● backend offline' : busy ? '● running: ' + busy : '● idle'}</div>
    </aside>
    <main>
      <div className="hero"><small>APPLICATION-LEVEL RECOVERY ASSESSMENT</small><h1>Kubernetes Backup, Restore, Cross-Cluster Migration &amp; Verification Framework</h1>
        <p>An application-aware recovery workflow that captures a healthy baseline, creates a Velero backup, simulates failure, restores the application into a separate Kubernetes cluster and independently verifies persistent data integrity and deployment readiness.</p></div>
      <div className="kpis">
        <div className="kpi"><small>Backup coverage</small><b>{latestB ? latestB.items + ' items' : '–'}</b><span>Latest completed Velero backup</span></div>
        <div className="kpi g"><small>Data baseline</small><b>{base ? base.records + ' rows' : '–'}</b><span>Healthy database records</span></div>
        <div className="kpi p"><small>Verification</small><b>{latest ? latest.checks : '–'}</b><span>Independent recovery checks</span></div>
        <div className="kpi o"><small>Restore</small><b>{restoreState}</b><span>Current restore state</span></div></div>
      {note && <p className="bad">{note}</p>}
      {busy && <div className="busybar"><span>⏳ Running: {busy}. Progress is in the Activity log.</span><button className="danger" onClick={cancel}>Cancel</button></div>}

      <Sec id="architecture" n="1" title="Recovery architecture" badge="Data flow"><div className="flow">
        <Node cls="" tag="Source cluster" name="kind-recovery-demo" text="Original healthy application and backup source." rows={[['App API', src && src.up ? 'Online' : 'Offline'], ['Records', src && src.up ? src.records : '–']]} />
        <div className="arrow">→<small>BACKUP</small></div>
        <Node cls="p" tag="Backup storage" name="Velero + MinIO" text="Local S3-compatible storage for Kubernetes backups." rows={[['Latest backup', latestB ? latestB.phase : '–'], ['Items', latestB ? latestB.items : '–']]} />
        <div className="arrow">→<small>RESTORE</small></div>
        <Node cls="t" tag="Target cluster" name="kind-recovery-target" text="Separate cluster receiving the recovered application." rows={[['App API', tgt && tgt.up ? 'Online' : 'Offline'], ['Records', tgt && tgt.up ? tgt.records : '–']]} />
        <div className="arrow">→<small>VERIFY</small></div>
        <Node cls="g" tag="Verification" name="Recovery assessment" text="Independent comparison against the healthy baseline." rows={CHECKS.map(([c]) => [c, chk(c) || '–'])} /></div></Sec>

      <Sec id="health" n="2" title="Application health" badge="Source / target"><div className="grid2">
        {[['Source API', src && src.up], ['Source records', src && src.up ? src.records : null], ['Target API', tgt && tgt.up], ['Target records', tgt && tgt.up ? tgt.records : null]].map(([t, v]) =>
          <div className="box" key={t}><span className="lbl">{t}</span><b className={v === false || v === null ? 'bad' : 'ok'}>{v === true ? 'Running' : v === false || v === null ? 'Unavailable' : v}</b></div>)}</div></Sec>

      <Sec id="baseline" n="3" title="Healthy baseline" badge="Reference state">
        <div className="note"><h5>HEALTHY APPLICATION REFERENCE</h5><p>The baseline is captured before recovery and is the reference for row count and data content.</p></div>
        <div className="grid2"><div className="box"><span className="lbl">Status</span><b><Pill v={base ? 'PASS' : null} /> {base ? 'Baseline ready' : 'Not captured'}</b></div>
          <div className="box"><span className="lbl">Database records</span><b>{base ? base.records : '–'}</b></div>
          <div className="box"><span className="lbl">Data fingerprint</span><b>{base ? base.fingerprint : '–'}</b></div>
          <div className="box"><span className="lbl">Captured</span><b>{base ? when(base.ts) : '–'}</b></div></div>
        <Btn n="baseline" label="Capture healthy baseline" /></Sec>

      <Sec id="backup" n="4" title="Backup" badge="Velero + MinIO">
        <div className="note"><h5>LATEST BACKUP</h5><p><b>{latestB ? latestB.name : 'No completed backup yet'}</b> {latestB && ' · ' + latestB.items + ' items · ' + when(latestB.created)}</p></div>
        <span className="lbl">Backup history</span><div className="hist">{backups.map(b => <div className="item" key={b.name}><div className="top"><span>{b.name}</span><Pill v={b.phase === 'Completed' ? 'PASS' : b.phase} /></div><span className="lbl">{b.items} items · {when(b.created)}</span></div>)}</div>
        <Btn n="backup" label="Create backup" /><button className="ghost" onClick={poll}>Refresh backups</button></Sec>

      <Sec id="failure" n="5" title="Failure simulation" badge="Disaster event">
        <div className="note warn"><h5>SIMULATE SOURCE APPLICATION FAILURE</h5><p>Scales the source UI, API and MySQL deployments to zero replicas. Persistent database data is intentionally preserved.</p><p style={{ marginTop: 8 }}>Source app: <Pill v={src && src.up} /></p></div>
        <Btn n="fail" label="Simulate failure" kind="danger" /><Btn n="heal" label="Bring source back" kind="ghost" /></Sec>

      <Sec id="restore" n="6" title="Cross-cluster restore" badge="Recovery"><div className="grid2">
        <div className="box"><span className="lbl">Source</span><b>kind-recovery-demo</b></div><div className="box"><span className="lbl">Target</span><b>kind-recovery-target</b></div>
        <div className="box"><span className="lbl">Restore status</span><b>{restoreState}</b></div><div className="box"><span className="lbl">Watched restore</span><b>{latest ? latest.restore_name : '–'}</b></div>
        <div className="box"><span className="lbl">Backup used</span><b>{latestB ? latestB.name : '–'}</b></div><div className="box"><span className="lbl">Observed restore time</span><b>{latest ? sec(latest.restore_s) : 'Waiting'}</b></div></div>
        <Btn n="restore" label="Restore application" /><Btn n="reset" label="Reset target (clean)" kind="ghost" />
        <p style={{ font: '11px var(--mono)', color: '#64748b' }}>Run Reset target before every restore. It removes the restored app and its data folder in the target cluster only.</p></Sec>

      <Sec id="metrics" n="7" title="Recovery metrics" badge="Real recovery data">
        <div className="kpis"><div className="kpi"><small>Total recovery runs</small><b>{recs.length}</b><span>Recorded by this dashboard</span></div>
          <div className="kpi g"><small>Successful runs</small><b>{ok}</b><span>Restore completed and verified</span></div>
          <div className="kpi p"><small>Latest restore</small><b>{latest ? sec(latest.restore_s) : '–'}</b><span>Velero restore + rollout</span></div>
          <div className="kpi o"><small>Latest verification</small><b>{latest ? sec(latest.verify_s) : '–'}</b><span>Duration of the checks</span></div></div>
        <span className="lbl">Recovery time history</span><Chart data={[...recs].reverse()} />
        <div className="scroll"><table><thead><tr><th>Restore</th><th>Result</th><th>Checks</th><th>Total</th><th>Restore</th><th>Verify</th></tr></thead>
          <tbody>{recs.map(r => <tr key={r.id}><td>{r.restore_name}</td><td><Pill v={r.result} /></td><td>{r.checks}</td><td>{sec(r.total_s)}</td><td>{sec(r.restore_s)}</td><td>{sec(r.verify_s)}</td></tr>)}</tbody></table></div></Sec>

      <Sec id="logs" n="8" title="Live recovery log" badge="Activity"><div className="term" ref={box}>
        <header><span>RECOVERY EVENT STREAM</span><em>● LIVE</em></header>
        {logs.map(l => { const k = kind(l); return <div className="l" key={l.id}><span className="t">{new Date(l.ts).toLocaleTimeString()}</span><span className={'sy ' + k[1]}>{k[0]}</span><span className={'m ' + k[1]}>{l.msg}</span></div>; })}</div></Sec>

      <Sec id="verification" n="9" title="Verification" badge="Independent checks">
        {CHECKS.map(([c, d]) => <div className="chk" key={c}><span><Pill v={chk(c)} /></span><span><b>{c}</b>{d}</span></div>)}
        <Btn n="verify" label="Run verification" />
        <span className="lbl" style={{ marginTop: 18 }}>Action history</span>
        <div className="scroll"><table><thead><tr><th>Step</th><th>Status</th><th>Time</th><th>Detail</th></tr></thead>
          <tbody>{runs.map(r => <tr key={r.id}><td>{r.step}</td><td><Pill v={r.status === 'OK' ? 'PASS' : 'FAIL'} /></td><td>{sec(r.seconds)}</td><td>{r.detail}</td></tr>)}</tbody></table></div></Sec>

      <Sec id="assessment" n="10" title="Final assessment" badge="Result">
        <div className={'final ' + (!latest ? 'n' : latest.result === 'PASS' ? '' : 'f')}><span className="lbl">Overall recovery result</span><b>{latest ? latest.result : '–'}</b>
          <span>{latest ? (latest.result === 'PASS' ? 'All recovery checks passed.' : 'One or more recovery checks failed.') : 'No restore has been run yet.'}</span>
          {lastPass && <p style={{ font: '11px var(--mono)', color: '#64748b' }}>Last verified successful recovery: {lastPass.restore_name}</p>}</div></Sec>
    </main></div>);
}
