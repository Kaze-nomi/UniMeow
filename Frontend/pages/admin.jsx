
function AdminPage({ currentUser, onNavigate, view }) {
  const [items, setItems] = React.useState([]);
  const [universities, setUniversities] = React.useState([]);
  const [loading, setLoading] = React.useState(true);
  const [error, setError] = React.useState('');

  const load = React.useCallback(() => {
    if (!currentUser?.isAdmin) {
      setLoading(false);
      setError('Недостаточно прав');
      return;
    }
    setLoading(true); setError('');
    if (view === 'universities') {
      Promise.all([
        API.gql(API.Q.adminUniversityProposals),
        API.gql(API.Q.adminFacultyProposals),
        API.gql(API.Q.adminProgramProposals),
      ])
        .then(([u, f, p]) => setItems([
          ...(u.adminUniversityProposals || []).map(item => ({ ...item, kind: 'university' })),
          ...(f.adminFacultyProposals || []).map(item => ({ ...item, kind: 'faculty' })),
          ...(p.adminProgramProposals || []).map(item => ({ ...item, kind: 'program' })),
        ].sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt))))
        .catch(e => setError(e.message))
        .finally(() => setLoading(false));
      return;
    }
    API.gql(API.Q.adminImprovementSuggestions)
      .then(d => setItems(d.adminImprovementSuggestions))
      .catch(e => setError(e.message))
      .finally(() => setLoading(false));
  }, [currentUser?.isAdmin, view]);

	  React.useEffect(load, [load]);
  React.useEffect(() => {
    if (!currentUser?.isAdmin || view !== 'universities') return;
    API.gql(API.Q.listUniversities)
      .then(d => setUniversities(d.listUniversities || []))
      .catch(() => setUniversities([]));
  }, [currentUser?.isAdmin, view]);
  React.useEffect(() => {
    const h = () => load();
    window.addEventListener('um-admin-refresh', h);
    return () => window.removeEventListener('um-admin-refresh', h);
  }, [load]);

  const review = async (id, status) => {
    await API.gql(API.M.adminReviewUniversityProposal, { proposalId: id, status });
    load();
    API.gql(API.Q.listUniversities).then(d => setUniversities(d.listUniversities || [])).catch(() => {});
  };
  const reviewFaculty = async (id, status) => {
    await API.gql(API.M.adminReviewFacultyProposal, { proposalId: id, status });
    load();
  };
  const reviewProgram = async (id, status) => {
    await API.gql(API.M.adminReviewProgramProposal, { proposalId: id, status });
    load();
  };
  const deleteSuggestion = async (id) => {
    await API.gql(API.M.adminDeleteSuggestion, { id });
    load();
  };

  const Header = () => (
    <div style={{ position: 'sticky', top: 0, zIndex: 10, background: 'var(--header-bg)', backdropFilter: 'saturate(180%) blur(12px)', WebkitBackdropFilter: 'saturate(180%) blur(12px)', borderBottom: '1px solid var(--border)', padding: '12px 16px' }} data-um-header>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        <button onClick={() => onNavigate('/')} style={{ background: 'none', border: 'none', cursor: 'pointer', padding: 6, color: 'var(--text)', display: 'inline-flex' }}>
          <BackArrowIcon size={20} color="var(--text)" />
        </button>
        <h2 style={{ margin: 0, fontSize: 20, fontWeight: 800 }}>{view === 'universities' ? 'ВУЗы и факультеты' : 'Предложения'}</h2>
      </div>
    </div>
  );

  if (!currentUser?.isAdmin) {
    return <><Header /><EmptyState icon={<ShieldIcon />} title="Недостаточно прав" subtitle="Эта страница доступна только администраторам" /></>;
  }

  return (
    <div>
      <Header />
      {loading ? (
        <div style={{ display: 'flex', justifyContent: 'center', padding: 48 }}><Spinner size={32} /></div>
      ) : error ? (
        <EmptyState icon={<ShieldIcon />} title="Не удалось загрузить" subtitle={error} />
      ) : items.length === 0 ? (
        <EmptyState icon={view === 'universities' ? <UniIcon /> : <StarIcon />} title="Пока пусто" />
      ) : (
        <div>
          {items.map(item => view === 'universities'
            ? (item.kind === 'faculty'
                ? <FacultyProposalRow key={'f-' + item.id} item={item} onReview={reviewFaculty} />
                : item.kind === 'program'
                ? <ProgramProposalRow key={'p-' + item.id} item={item} onReview={reviewProgram} />
                : <UniversityProposalRow key={'u-' + item.id} item={item} onReview={review} onNavigate={onNavigate} />)
            : <SuggestionRow key={item.id} item={item} onDelete={deleteSuggestion} />
          )}
        </div>
      )}
    </div>
  );
}

function useAuthorName(authorId) {
  const [name, setName] = React.useState('@' + authorId);
  React.useEffect(() => {
    API.getCachedUser(authorId).then(u => { if (u) setName('@' + (u.username || authorId)); });
  }, [authorId]);
  return name;
}

function FacultyProposalRow({ item, onReview }) {
  const done = item.status !== 'NEW';
  const authorName = useAuthorName(item.authorId);
  return (
    <article className="um-feed-row" style={{ padding: 16, borderBottom: '1px solid var(--border)', background: 'var(--bg)' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, alignItems: 'flex-start' }}>
        <div>
          <div style={{ fontWeight: 800, fontSize: 16 }}>Факультет: {item.name}</div>
          <div style={{ color: 'var(--text-muted)', fontSize: 13 }}>short: {item.shortName}</div>
          <div style={{ color: 'var(--text-muted)', fontSize: 13 }}>университет: {item.universityName} ({item.universityShortName})</div>
        </div>
        <Badge color={item.status === 'APPROVED' ? 'green' : item.status === 'REJECTED' ? 'red' : 'accent'}>{item.status}</Badge>
      </div>
      <div style={{ marginTop: 10, color: 'var(--text-muted)', fontSize: 12 }}>Автор: {authorName} · {new Date(normalizeIsoDate(item.createdAt)).toLocaleString('ru-RU', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' })}</div>
      {!done && (
        <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
          <Button size="sm" onClick={() => onReview(item.id, 'APPROVED')}>Одобрить и добавить факультет</Button>
          <Button size="sm" variant="secondary" onClick={() => onReview(item.id, 'REJECTED')}>Отклонить</Button>
        </div>
      )}
    </article>
  );
}

function ProgramProposalRow({ item, onReview }) {
  const done = item.status !== 'NEW';
  const authorName = useAuthorName(item.authorId);
  return (
    <article className="um-feed-row" style={{ padding: 16, borderBottom: '1px solid var(--border)', background: 'var(--bg)' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, alignItems: 'flex-start' }}>
        <div>
          <div style={{ fontWeight: 800, fontSize: 16 }}>Программа: {item.name}</div>
          <div style={{ color: 'var(--text-muted)', fontSize: 13 }}>short: {item.shortName}</div>
          <div style={{ color: 'var(--text-muted)', fontSize: 13 }}>факультет: {item.facultyName} ({item.facultyShortName})</div>
          <div style={{ color: 'var(--text-muted)', fontSize: 13 }}>университет: {item.universityName} ({item.universityShortName})</div>
        </div>
        <Badge color={item.status === 'APPROVED' ? 'green' : item.status === 'REJECTED' ? 'red' : 'accent'}>{item.status}</Badge>
      </div>
      <div style={{ marginTop: 10, color: 'var(--text-muted)', fontSize: 12 }}>Автор: {authorName} · {new Date(normalizeIsoDate(item.createdAt)).toLocaleString('ru-RU', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' })}</div>
      {!done && (
        <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
          <Button size="sm" onClick={() => onReview(item.id, 'APPROVED')}>Одобрить и добавить программу</Button>
          <Button size="sm" variant="secondary" onClick={() => onReview(item.id, 'REJECTED')}>Отклонить</Button>
        </div>
      )}
    </article>
  );
}

function SuggestionRow({ item, onDelete }) {
  const [deleting, setDeleting] = React.useState(false);
  const [confirmOpen, setConfirmOpen] = React.useState(false);
  const authorName = useAuthorName(item.authorId);
  const handleDelete = async () => {
    setDeleting(true);
    try {
      await onDelete(item.id);
      setConfirmOpen(false);
    } finally {
      setDeleting(false);
    }
  };
  return (
    <>
      <article className="um-feed-row" style={{ padding: 16, borderBottom: '1px solid var(--border)', background: 'var(--bg)' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, marginBottom: 8, alignItems: 'flex-start' }}>
          <div style={{ fontWeight: 700 }}>Предложение</div>
          <button onClick={() => setConfirmOpen(true)} disabled={deleting} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'oklch(0.6 0.18 15)', padding: 4, borderRadius: 6, display: 'inline-flex' }}>
            <TrashIcon size={16} />
          </button>
        </div>
        <p style={{ margin: 0, whiteSpace: 'pre-wrap', lineHeight: 1.55 }}>{item.text}</p>
        <div style={{ marginTop: 10, color: 'var(--text-muted)', fontSize: 12 }}>Автор: {authorName} · {new Date(normalizeIsoDate(item.createdAt)).toLocaleString('ru-RU', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' })}</div>
      </article>
      <Modal open={confirmOpen} onClose={() => !deleting && setConfirmOpen(false)} title="Удалить предложение">
        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <div style={{ color: 'var(--text-muted)', fontSize: 14, lineHeight: 1.5 }}>
            Предложение будет удалено из админской очереди без возможности восстановления.
          </div>
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
            <Button variant="secondary" onClick={() => setConfirmOpen(false)} disabled={deleting}>Отмена</Button>
            <Button variant="danger" onClick={handleDelete} loading={deleting}>Удалить</Button>
          </div>
        </div>
      </Modal>
    </>
  );
}

function UniversityProposalRow({ item, onReview, onNavigate }) {
  const done = item.status !== 'NEW';
  const iconUrl = API.resolveAssetUrl(item.iconUrl);
  const authorName = useAuthorName(item.authorId);
  return (
    <article className="um-feed-row" style={{ padding: 16, borderBottom: '1px solid var(--border)', background: 'var(--bg)' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, alignItems: 'flex-start' }}>
        <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start' }}>
          {iconUrl && <img src={iconUrl} alt="" style={{ width: 40, height: 40, borderRadius: 10, objectFit: 'cover', display: 'block' }} />}
          <div>
            <div style={{ fontWeight: 800, fontSize: 16 }}>{item.name}</div>
            <div style={{ color: 'var(--text-muted)', fontSize: 13 }}>short: {item.shortName}</div>
            <div style={{ color: 'var(--text-muted)', fontSize: 13 }}>subdomain: {item.subdomain
              ? <span onClick={() => onNavigate('/' + item.subdomain + '/')} style={{ color: 'var(--accent)', cursor: 'pointer', textDecoration: 'underline' }}>{item.subdomain}</span>
              : '—'}</div>
            <div style={{ color: 'var(--text-muted)', fontSize: 13 }}>student: {item.studentDomain}</div>
            <div style={{ color: 'var(--text-muted)', fontSize: 13 }}>employee: {item.employeeDomain || '—'}</div>
          </div>
        </div>
        <Badge color={item.status === 'APPROVED' ? 'green' : item.status === 'REJECTED' ? 'red' : 'accent'}>{item.status}</Badge>
      </div>
      <div style={{ marginTop: 10, color: 'var(--text-muted)', fontSize: 12 }}>Автор: {authorName} · {new Date(normalizeIsoDate(item.createdAt)).toLocaleString('ru-RU', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' })}</div>
      {!done && (
        <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
          <Button size="sm" onClick={() => onReview(item.id, 'APPROVED')}>Одобрить и добавить университет</Button>
          <Button size="sm" variant="secondary" onClick={() => onReview(item.id, 'REJECTED')}>Отклонить</Button>
        </div>
      )}
    </article>
  );
}

Object.assign(window, { AdminPage });
