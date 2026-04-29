

function ProfilePage({ userId, currentUser, onNavigate, onUserUpdated }) {
  const [user, setUser] = React.useState(null);
  const [posts, setPosts] = React.useState([]);
  const [loading, setLoading] = React.useState(true);
  const [postsLoading, setPostsLoading] = React.useState(false);
  const [subscribed, setSubscribed] = React.useState(false);
  const [subLoading, setSubLoading] = React.useState(false);
  const [error, setError] = React.useState('');
  const [editOpen, setEditOpen] = React.useState(false);
  const [verifyOpen, setVerifyOpen] = React.useState(false);
  const [banOpen, setBanOpen] = React.useState(false);
  const [banDays, setBanDays] = React.useState('');
  const [banReason, setBanReason] = React.useState('Нарушение правил');
  const [banLoading, setBanLoading] = React.useState(false);

  const isMe = currentUser && currentUser.id === userId;

  React.useEffect(() => {
    setLoading(true); setError(''); setUser(null); setPosts([]);
    API.gql(API.Q.getUser, { id: userId })
      .then(d => { setUser(d.getUser); setSubscribed(!!d.getUser.isFollowedByMe); loadPosts(d.getUser.id); })
      .catch(e => setError(e.message))
      .finally(() => setLoading(false));
  }, [userId]);


  React.useEffect(() => {
    if (isMe && currentUser) setUser(currentUser);
  }, [currentUser, isMe]);

  const loadPosts = async (uid) => {
    setPostsLoading(true);
    try {
      const d = await API.gql(API.Q.getUserPosts, { userId: uid, size: 20 });
      setPosts(d.getUserPosts.posts);
    } catch {} finally { setPostsLoading(false); }
  };

  const handleSubscribe = async () => {
    if (!currentUser) { onNavigate('/login'); return; }
    setSubLoading(true);
    try {
      if (subscribed) { await API.gql(API.M.unsubscribe, { id: userId }); setSubscribed(false); }
      else            { await API.gql(API.M.subscribe,   { id: userId }); setSubscribed(true);  }
    } catch (e) { if (e.isUnauth) onNavigate('/login'); }
    finally { setSubLoading(false); }
  };

  const handleBan = async () => {
    setBanDays('');
    setBanReason('Нарушение правил');
    setBanOpen(true);
  };

  const submitBan = async () => {
    setBanLoading(true);
    const reason = banReason.trim();
    const days = banDays.trim();
    const input = { targetUserId: user.id, reason };
    if (days && !Number.isNaN(Number(days)) && Number(days) > 0) {
      const until = new Date(Date.now() + Number(days) * 24 * 60 * 60 * 1000);
      input.bannedUntil = until.toISOString().slice(0, 19);
    }
    try {
      await API.gql(API.M.adminBanUser, { input });
      setUser(u => ({ ...u, isBanned: true, banReason: reason }));
      setBanOpen(false);
    } finally {
      setBanLoading(false);
    }
  };

  if (loading) return <div style={{ display: 'flex', justifyContent: 'center', padding: 64 }}><Spinner size={36} /></div>;
  if (error || !user) return <EmptyState icon={<CatFaceIcon />} title="Пользователь не найден" subtitle={error} />;

  const displayName = (user.name && user.surname) ? `${user.name} ${user.surname}` : user.name || user.username || 'Пользователь';
  const joinDate = user.createdAt ? new Date(user.createdAt).toLocaleDateString('ru-RU', { month: 'long', year: 'numeric' }) : '';
  const accentHue = getComputedStyle(document.documentElement).getPropertyValue('--accent-hue').trim() || '288';

  return (
    <div>

      <div style={{
        position: 'sticky', top: 0, zIndex: 10,
        background: 'var(--header-bg)', backdropFilter: 'saturate(180%) blur(12px)', WebkitBackdropFilter: 'saturate(180%) blur(12px)',
        borderBottom: '1px solid var(--border)', display: 'flex', alignItems: 'center', gap: 16, padding: '8px 16px',
      }} data-um-header>
        <button onClick={() => onNavigate(-1)} style={{ background: 'none', border: 'none', cursor: 'pointer', padding: 8, borderRadius: '50%', color: 'var(--text)' }}>
          <BackArrowIcon size={20} color="var(--text)" />
        </button>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontSize: 18, fontWeight: 800, lineHeight: 1.2, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{displayName}</div>
          <div style={{ fontSize: 13, color: 'var(--text-muted)' }}>{posts.length} {pluralize(posts.length, 'запись', 'записи', 'записей')}</div>
        </div>
      </div>


      {user.coverUrl ? (
        <div style={{ height: 200, overflow: 'hidden' }}>
          <img src={user.coverUrl} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover', display: 'block' }} />
        </div>
      ) : (
        <div style={{
          height: 200,
          background: `linear-gradient(135deg, oklch(0.55 0.22 ${accentHue}) 0%, oklch(0.65 0.18 ${(parseInt(accentHue) + 30) % 360}) 100%)`,
        }} />
      )}


      <div style={{ padding: '0 16px', position: 'relative' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
          <div style={{
            marginTop: -68, padding: 4,
            background: 'var(--bg)', borderRadius: '50%', display: 'inline-block',
          }}>
            <Avatar user={user} size={128} />
          </div>
          <div style={{ marginTop: 12, display: 'flex', gap: 8, flexWrap: 'wrap', justifyContent: 'flex-end' }}>
            {isMe ? (
              <>
                {!user.isStudentVerified && !user.isEmployeeVerified && (
                  <Button size="sm" variant="outline" onClick={() => setVerifyOpen(true)}>
                    <ShieldIcon size={13} /> Верификация
                  </Button>
                )}
                <Button size="sm" variant="outline" onClick={() => setEditOpen(true)}>
                  Редактировать
                </Button>
              </>
            ) : (
              <>
                {currentUser?.isAdmin && !user.isAdmin && (
                  <Button size="sm" variant="secondary" onClick={handleBan}>Бан</Button>
                )}
                <Button size="sm" variant={subscribed ? 'secondary' : 'primary'} onClick={handleSubscribe} loading={subLoading}>
                  {subscribed ? 'Вы подписаны' : 'Подписаться'}
                </Button>
              </>
            )}
          </div>
        </div>


        <div style={{ marginTop: 12 }}>
          {user.isBanned && (
            <div style={{ marginBottom: 10, padding: '8px 10px', borderRadius: 8, border: '1px solid var(--border)', color: 'var(--like)', fontSize: 13 }}>
              Пользователь забанен{user.banReason ? `: ${user.banReason}` : ''}
            </div>
          )}
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
            <h1 style={{ margin: 0, fontSize: 22, fontWeight: 800, lineHeight: 1.2 }}>{displayName}</h1>
            {user.isStudentVerified && (
              <span title="Студент верифицирован" style={{ display: 'inline-flex', background: 'var(--accent)', color: '#fff', borderRadius: '50%', width: 22, height: 22, alignItems: 'center', justifyContent: 'center' }}>
                <CheckIcon size={14} color="#fff" />
              </span>
            )}
            {user.isEmployeeVerified && (
              <span title="Сотрудник/преподаватель верифицирован" style={{ display: 'inline-flex', background: 'oklch(0.55 0.18 150)', color: '#fff', borderRadius: '50%', width: 22, height: 22, alignItems: 'center', justifyContent: 'center' }}>
                <TeacherIcon size={13} color="#fff" />
              </span>
            )}
          </div>
          {user.username && <div style={{ color: 'var(--text-muted)', fontSize: 15 }}>@{user.username}</div>}
        </div>

        {user.bio && <p style={{ margin: '12px 0 0', fontSize: 15, lineHeight: 1.45, color: 'var(--text)', whiteSpace: 'pre-wrap' }}>{user.bio}</p>}
        {user.status && <p style={{ margin: '8px 0 0', fontSize: 14, color: 'var(--text-muted)' }}>«{user.status}»</p>}


        <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap', fontSize: 14, color: 'var(--text-muted)', marginTop: 12 }}>
          {user.university?.name && <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>{user.university.shortName ? <UniBadge uni={user.university} size={20} /> : <UniIcon size={18} />} {user.university.name}</span>}
          {user.faculty?.name && <span>Факультет: {user.faculty.name}</span>}
          {user.course && <span>{user.course} курс</span>}
          {user.educationLevel && <span>{eduLabel(user.educationLevel)}</span>}
          {joinDate && <span>с {joinDate}</span>}
        </div>
      </div>


      {postsLoading ? (
        <div style={{ display: 'flex', justifyContent: 'center', padding: 32 }}><Spinner /></div>
      ) : posts.length === 0 ? (
        <EmptyState icon={<EditIcon size={40} />} title="Нет записей" subtitle={isMe ? 'Напишите первую запись!' : undefined} />
      ) : (
        posts.map(post => (
          <PostCard key={post.id} post={post} onNavigate={onNavigate} currentUser={currentUser} />
        ))
      )}

      {isMe && <EditProfileModal open={editOpen} onClose={() => setEditOpen(false)} currentUser={currentUser} onUserUpdated={onUserUpdated} />}
      {isMe && <VerifyModal open={verifyOpen} onClose={() => setVerifyOpen(false)} currentUser={currentUser} onUserUpdated={onUserUpdated} />}
      <Modal open={banOpen} onClose={() => !banLoading && setBanOpen(false)} title="Заблокировать пользователя">
        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <div style={{ color: 'var(--text-muted)', fontSize: 14, lineHeight: 1.5 }}>
            Блокировка будет применена к @{user.username || user.id}. Оставьте срок пустым, если бан должен быть постоянным.
          </div>
          <Input
            label="Срок бана в днях"
            type="number"
            value={banDays}
            onChange={e => setBanDays(e.target.value)}
            placeholder="Например, 7"
          />
          <Input
            label="Причина"
            multiline
            rows={4}
            value={banReason}
            onChange={e => setBanReason(e.target.value)}
            placeholder="Причина блокировки"
          />
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
            <Button variant="secondary" onClick={() => setBanOpen(false)} disabled={banLoading}>Отмена</Button>
            <Button variant="danger" onClick={submitBan} loading={banLoading}>Заблокировать</Button>
          </div>
        </div>
      </Modal>
    </div>
  );
}

function eduLabel(level) {
  return { BACHELOR: 'Бакалавр', MASTER: 'Магистр', PHD: 'Аспирант', SPECIALIST: 'Специалист' }[level] || level;
}

function pluralize(n, one, few, many) {
  const mod10 = n % 10, mod100 = n % 100;
  if (mod10 === 1 && mod100 !== 11) return one;
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 10 || mod100 >= 20)) return few;
  return many;
}

Object.assign(window, { ProfilePage, pluralize });
