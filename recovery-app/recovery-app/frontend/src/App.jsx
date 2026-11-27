import { useEffect, useState, useCallback } from 'react';
const empty = { name:'', email:'', city:'', amount:'' };

export default function App() {
  const [rows, setRows] = useState([]);
  const [stats, setStats] = useState(null);
  const [form, setForm] = useState(empty);
  const [editId, setEditId] = useState(null);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    try {
      const [r, s] = await Promise.all([fetch('/api/records'), fetch('/api/stats')]);
      if (!r.ok || !s.ok) throw new Error('API returned ' + (r.ok ? s.status : r.status));
      setRows(await r.json()); setStats(await s.json()); setError('');
    } catch (e) { setError('Cannot reach the API: ' + e.message); }
  }, []);
  useEffect(() => { load(); const t = setInterval(load, 10000); return () => clearInterval(t); }, [load]);

  const save = async (e) => {
    e.preventDefault();
    const body = JSON.stringify({ ...form, amount: Number(form.amount) });
    await fetch(editId ? `/api/records/${editId}` : '/api/records',
      { method: editId ? 'PUT' : 'POST', headers: { 'Content-Type': 'application/json' }, body });
    setForm(empty); setEditId(null); load();
  };
  const remove = async (id) => { await fetch(`/api/records/${id}`, { method: 'DELETE' }); load(); };
  const edit = (r) => { setEditId(r.id); setForm({ name:r.name, email:r.email, city:r.city, amount:r.amount }); };

  return (
    <main>
      <header>
        <h1>Customer records</h1>
        <div className="meta">
          <span className="count">{stats ? stats.records : '–'} records</span>
          <span>Cluster: <b>{stats ? stats.cluster : 'offline'}</b></span>
          <span className="fp" title={stats?.fingerprint}>Fingerprint {stats ? stats.fingerprint.slice(0, 16) + '…' : '–'}</span>
        </div>
      </header>
      {error && <p className="error">{error} Start the app or finish the restore, then this page refreshes itself.</p>}
      <form onSubmit={save}>
        {['name','email','city'].map(k => (
          <input key={k} placeholder={k} required value={form[k]} onChange={e => setForm({ ...form, [k]: e.target.value })} />))}
        <input placeholder="amount" type="number" step="0.01" required value={form.amount} onChange={e => setForm({ ...form, amount: e.target.value })} />
        <button>{editId ? 'Save changes' : 'Add record'}</button>
        {editId && <button type="button" className="ghost" onClick={() => { setEditId(null); setForm(empty); }}>Cancel</button>}
      </form>
      <div className="scroll">
        <table>
          <thead><tr><th>ID</th><th>Name</th><th>Email</th><th>City</th><th className="num">Amount</th><th></th></tr></thead>
          <tbody>{rows.map(r => (
            <tr key={r.id}><td>{r.id}</td><td>{r.name}</td><td>{r.email}</td><td>{r.city}</td>
              <td className="num">{Number(r.amount).toFixed(2)}</td>
              <td className="act"><button className="ghost" onClick={() => edit(r)}>Edit</button><button className="ghost danger" onClick={() => remove(r.id)}>Delete</button></td></tr>))}
          </tbody>
        </table>
      </div>
    </main>
  );
}
