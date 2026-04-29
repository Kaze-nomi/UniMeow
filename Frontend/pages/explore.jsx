function ExplorePage({ currentUser, onNavigate }) {
  const [query, setQuery] = React.useState('');
  const [result, setResult] = React.useState(null);
  const [loading, setLoading] = React.useState(false);
  const [error, setError] = React.useState('');

  const search = async () => {
    const q = query.trim().replace(/^@/, '');
    if (!q) return;
    setLoading(true); setError(''); setResult(null);
    try {
      const d = await API.gql(API.Q.getUserByUsername, { username: q });
      setResult(d.getUserByUsername);
      if (!d.getUserByUsername) setError('Пользователь не найден');
    } catch (e) { setError('Пользователь не найден'); } finally { setLoading(false); }
  };

  const handleKey = (e) => { if (e.key === 'Enter') search(); };

  const displayName = result ? ((result.name && result.surname) ? `${result.name} ${result.surname}` : result.name || result.username) : '';

  return (
    <div>
      <div style={{ position: 'sticky', top: 0, zIndex: 10, background: 'var(--header-bg)', backdropFilter: 'saturate(180%) blur(12px)', WebkitBackdropFilter: 'saturate(180%) blur(12px)', borderBottom: '1px solid var(--border)', padding: '12px 16px' }} data-um-header>
        <h2 style={{ margin: '0 0 10px', fontSize: 22, fontWeight: 800 }}>Поиск</h2>
        <div style={{ display: 'flex', gap: 8 }}>
          <div style={{ flex: 1, position: 'relative' }}>
            <SearchIcon size={18} color="var(--text-muted)" style={{ position: 'absolute', left: 14, top: '50%', transform: 'translateY(-50%)' }} />
            <input
              value={query} onChange={e => setQuery(e.target.value)} onKeyDown={handleKey}
              placeholder="Поиск по @username"
              autoFocus
              style={{
                width: '100%', padding: '11px 16px 11px 42px', borderRadius: 9999,
                border: '1.5px solid transparent', background: 'var(--surface-2)',
                color: 'var(--text)', fontFamily: 'inherit', fontSize: 15, outline: 'none',
                boxSizing: 'border-box',
              }}
            />
          </div>
          <Button onClick={search} loading={loading} disabled={!query.trim()}>
            Найти
          </Button>
        </div>
      </div>

      {error && <EmptyState icon={<CatFaceIcon />} title={error} subtitle="Попробуйте другой никнейм" />}

      {result && (
        <div
          className="um-feed-row"
          onClick={() => onNavigate('/profile/' + result.id)}
          style={{ background: 'var(--bg)', borderBottom: '1px solid var(--border)', padding: 16, cursor: 'pointer' }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
            <Avatar user={result} size={52} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 3, flexWrap: 'wrap' }}>
                <span style={{ fontWeight: 700, fontSize: 17 }}>{displayName}</span>
                {result.isStudentVerified && <Badge color="accent"><CheckIcon size={10} /> Студент</Badge>}
                {result.isEmployeeVerified && <Badge color="green"><ShieldIcon size={10} /> Сотрудник</Badge>}
              </div>
              <div style={{ fontSize: 14, color: 'var(--text-muted)', marginBottom: 4 }}>@{result.username}</div>
              {result.bio && <div style={{ fontSize: 14, color: 'var(--text)', lineHeight: 1.5 }}>{result.bio}</div>}
            </div>
            <div style={{ color: 'var(--text-muted)', fontSize: 20 }}>→</div>
          </div>
        </div>
      )}

      {!result && !error && !loading && (
        <div style={{ textAlign: 'center', padding: '48px 24px', color: 'var(--text-muted)' }}>
          <div style={{ display: 'flex', justifyContent: 'center', marginBottom: 12 }}><SearchIcon size={36} color="var(--text-muted)" /></div>
          <div style={{ fontWeight: 600, fontSize: 15, color: 'var(--text)', marginBottom: 6 }}>Найдите однокурсников</div>
          <div style={{ fontSize: 14 }}>Введите @username и нажмите Enter</div>
        </div>
      )}
    </div>
  );
}

Object.assign(window, { ExplorePage });


