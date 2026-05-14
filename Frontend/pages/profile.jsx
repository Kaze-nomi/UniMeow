const subscriptionButtonStyle = {
  background: 'none',
  border: 'none',
  padding: 0,
  color: 'var(--text)',
  cursor: 'pointer',
  fontFamily: 'inherit',
  fontSize: 14,
  fontWeight: 700,
};

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
  const [banError, setBanError] = React.useState('');
  const [banLoading, setBanLoading] = React.useState(false);
  const [grantAdminLoading, setGrantAdminLoading] = React.useState(false);
  const [subscriptionsOpen, setSubscriptionsOpen] = React.useState(false);
  const [subscriptionsType, setSubscriptionsType] = React.useState('following');
  const [subscriptions, setSubscriptions] = React.useState([]);
  const [subscriptionsLoading, setSubscriptionsLoading] = React.useState(false);
  const [subscriptionsError, setSubscriptionsError] = React.useState('');

  const isUuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(userId);
  const isMe = currentUser && (currentUser.id === userId || currentUser.username === userId);

  React.useEffect(() => {
    setLoading(true); setError(''); setUser(null); setPosts([]);
    const query = isUuid
      ? API.gql(API.Q.getUser, { id: userId }).then(d => d.getUser)
      : API.gql(API.Q.getUserByUsername, { username: userId }).then(d => d.getUserByUsername);
    query
      .then(u => { if (!u) throw new Error('Пользователь не найден'); setUser(u); setSubscribed(!!u.isFollowedByMe); loadPosts(u.id); })
      .catch(e => setError(e.message || 'Пользователь не найден'))
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
      const targetId = user?.id || userId;
      if (subscribed) { await API.gql(API.M.unsubscribe, { id: targetId }); setSubscribed(false); }
      else            { await API.gql(API.M.subscribe,   { id: targetId }); setSubscribed(true);  }
    } catch (e) { if (e.isUnauth) onNavigate('/login'); }
    finally { setSubLoading(false); }
  };

  const openSubscriptions = async (type) => {
    if (!user?.id) return;
    setSubscriptionsType(type);
    setSubscriptionsOpen(true);
    setSubscriptionsLoading(true);
    setSubscriptionsError('');
    try {
      const d = await API.gql(type === 'followers' ? API.Q.listFollowers : API.Q.listFollowing, { userId: user.id });
      setSubscriptions(type === 'followers' ? d.listFollowers : d.listFollowing);
    } catch (e) {
      setSubscriptions([]);
      setSubscriptionsError(e.message || 'Не удалось загрузить список');
    } finally {
      setSubscriptionsLoading(false);
    }
  };

  const updatePostLikeState = React.useCallback((postId, patch) => {
    setPosts(prev => prev.map(post => post.id === postId ? { ...post, ...patch } : post));
  }, []);

  const handleBan = async () => {
    setBanDays('');
    setBanReason('Нарушение правил');
    setBanError('');
    setBanOpen(true);
  };

  const handleBanDaysChange = (event) => {
    const next = event.target.value.trim();
    if (!/^\d*$/.test(next)) {
      setBanError('Введите целое число дней от 1 до 3650 или оставьте поле пустым для бессрочного бана.');
      return;
    }
    setBanDays(next);
    setBanError('');
  };

  const handleGrantAdmin = async (targetId) => {
    setGrantAdminLoading(true);
    try {
      const d = await API.gql(API.M.adminGrantAdmin, { targetUserId: targetId });
      if (isMe) {
        onUserUpdated && onUserUpdated(d.adminGrantAdmin);
      } else {
        setUser(u => ({ ...u, isAdmin: true }));
      }
    } finally {
      setGrantAdminLoading(false);
    }
  };

  const submitBan = async () => {
    setBanLoading(true);
    if (banError) {
      setBanLoading(false);
      return;
    }
    setBanError('');
    const reason = banReason.trim();
    const days = banDays.trim();
    const input = { targetUserId: user.id, reason };
    let bannedUntil = null;
    if (days) {
      const daysNumber = Number(days);
      if (!/^[1-9]\d*$/.test(days) || !Number.isSafeInteger(daysNumber) || daysNumber > 3650) {
        setBanError('Введите целое число дней от 1 до 3650 или оставьте поле пустым для бессрочного бана.');
        setBanLoading(false);
        return;
      }
      const until = new Date(Date.now() + daysNumber * 24 * 60 * 60 * 1000);
      bannedUntil = until.toISOString().slice(0, 19);
      input.bannedUntil = bannedUntil;
    }
    try {
      await API.gql(API.M.adminBanUser, { input });
      if (bannedUntil) {
        setUser(u => ({ ...u, isBanned: true, banReason: reason || null, bannedUntil }));
      } else {
        setPosts([]);
        setUser(null);
        setError('Пользователь не найден');
      }
      setBanOpen(false);
    } catch (e) {
      setBanError(API.userMessage ? API.userMessage(e.message) : e.message);
    } finally {
      setBanLoading(false);
    }
  };

  if (loading) return <div style={{ display: 'flex', justifyContent: 'center', padding: 64 }}><Spinner size={36} /></div>;
  if (error || !user) return <EmptyState icon={<svg width={56} height={56} viewBox="0 0 24 24" fill="none" stroke="var(--text-muted)" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="8" r="4"/><path d="M5.5 20a6.5 6.5 0 0113 0"/></svg>} title="Пользователь не найден" subtitle={error} />;

  const displayName = (user.name && user.surname) ? `${user.name} ${user.surname}` : user.name || user.username || 'Пользователь';
  const joinDate = user.createdAt ? new Date(user.createdAt).toLocaleDateString('ru-RU', { month: 'long', year: 'numeric' }) : '';
  const banUntilText = formatProfileBanDate(user.bannedUntil);
  const currentUserIsRoot = currentUser?.username?.toLowerCase() === 'kazenomi';
  const targetUserIsRoot = user.username?.toLowerCase() === 'kazenomi';
  const canBanProfile = currentUser?.isAdmin && !isMe && !targetUserIsRoot
    && (currentUserIsRoot || !user.isAdmin);
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
                {currentUser?.username === 'kazenomi' && !user.isAdmin && (
                  <Button size="sm" variant="secondary" onClick={() => handleGrantAdmin(user?.id || userId)} loading={grantAdminLoading}>
                    Получить права администратора
                  </Button>
                )}
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
                  <>
                    {currentUser?.username === 'kazenomi' && (
                      <Button size="sm" variant="secondary" onClick={() => handleGrantAdmin(user?.id || userId)} loading={grantAdminLoading}>Выдать админку</Button>
                    )}
                  </>
                )}
                {canBanProfile && (
                  <Button size="sm" variant="secondary" onClick={handleBan}>Бан</Button>
                )}
                {!user.isBanned && (
                  <Button size="sm" variant={subscribed ? 'secondary' : 'primary'} onClick={handleSubscribe} loading={subLoading}>
                    {subscribed ? 'Вы подписаны' : 'Подписаться'}
                  </Button>
                )}
              </>
            )}
          </div>
        </div>


        <div style={{ marginTop: 12 }}>
          {user.isBanned && (
            <div style={{ marginBottom: 10, padding: '10px 12px', borderRadius: 10, border: '1px solid var(--border)', color: 'var(--like)', background: 'var(--like-subtle)', fontSize: 13, lineHeight: 1.45 }}>
              <div style={{ fontWeight: 800 }}>Пользователь заблокирован</div>
              <div>Причина: {user.banReason || 'не указана'}</div>
              <div>{banUntilText ? `Разблокировка: ${banUntilText}` : 'Блокировка бессрочная'}</div>
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
        {user.status && <p style={{
          margin: '8px 0 0',
          maxWidth: '100%',
          fontSize: 14,
          lineHeight: 1.45,
          color: 'var(--text-muted)',
          overflow: 'hidden',
          overflowWrap: 'anywhere',
          wordBreak: 'break-word',
          display: '-webkit-box',
          WebkitBoxOrient: 'vertical',
          WebkitLineClamp: 3,
        }}>«{user.status}»</p>}


        <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap', fontSize: 14, color: 'var(--text-muted)', marginTop: 12 }}>
          {user.university?.name && <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>{user.university.shortName ? <UniBadge uni={user.university} size={20} /> : <UniIcon size={18} />} {user.university.name}</span>}
          {user.faculty?.name && <span>Факультет: {user.faculty.name}</span>}
          {user.program?.name && <span>Программа: {user.program.name}</span>}
          {user.course && <span>{user.course} курс</span>}
          {user.educationLevel && <span>{eduLabel(user.educationLevel)}</span>}
          {joinDate && <span>с {joinDate}</span>}
        </div>
        <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginTop: 12 }}>
          <button onClick={() => openSubscriptions('following')} style={subscriptionButtonStyle}>
            Подписки
          </button>
          <button onClick={() => openSubscriptions('followers')} style={subscriptionButtonStyle}>
            Подписчики
          </button>
        </div>
      </div>


      <div style={{ height: 1, background: 'var(--border)', margin: '16px 0 0' }} />

      {postsLoading ? (
        <div style={{ display: 'flex', justifyContent: 'center', padding: 32 }}><Spinner /></div>
      ) : posts.length === 0 ? (
        <EmptyState icon={<EditIcon size={40} />} title="Нет записей" subtitle={isMe ? 'Напишите первую запись!' : undefined} />
      ) : (
        posts.map(post => (
          <PostCard key={post.id} post={post} onNavigate={onNavigate} currentUser={currentUser}
            onLike={updatePostLikeState} />
        ))
      )}

      {isMe && <EditProfileModal open={editOpen} onClose={() => setEditOpen(false)} currentUser={currentUser} onUserUpdated={onUserUpdated} />}
      {isMe && <VerifyModal open={verifyOpen} onClose={() => setVerifyOpen(false)} currentUser={currentUser} onUserUpdated={onUserUpdated} />}
      <Modal open={subscriptionsOpen} onClose={() => setSubscriptionsOpen(false)}
        title={subscriptionsType === 'followers' ? 'Подписчики' : 'Подписки'}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {subscriptionsLoading ? (
            <div style={{ display: 'flex', justifyContent: 'center', padding: 24 }}><Spinner size={24} /></div>
          ) : subscriptionsError ? (
            <div style={{ padding: '10px 14px', background: 'var(--like-subtle)', color: 'var(--like)', borderRadius: 10, fontSize: 13 }}>{subscriptionsError}</div>
          ) : subscriptions.length === 0 ? (
            <div style={{ padding: '20px 8px', color: 'var(--text-muted)', fontSize: 14, textAlign: 'center' }}>
              {subscriptionsType === 'followers' ? 'Подписчиков пока нет' : 'Подписок пока нет'}
            </div>
          ) : (
            subscriptions.map(item => (
              <button key={item.id} onClick={() => { setSubscriptionsOpen(false); onNavigate(API.profileUrl(item)); }} style={{
                display: 'flex', alignItems: 'center', gap: 12, padding: '10px 4px',
                border: 'none', background: 'transparent', cursor: 'pointer', textAlign: 'left',
                color: 'var(--text)', fontFamily: 'inherit',
              }}>
                <Avatar user={item} size={42} />
                <div style={{ minWidth: 0, flex: 1 }}>
                  <div style={{ fontWeight: 700, fontSize: 14, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                    {(item.name && item.surname) ? `${item.name} ${item.surname}` : item.name || item.username || 'Пользователь'}
                  </div>
                  <div style={{ fontSize: 13, color: 'var(--text-muted)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                    @{item.username || item.id}
                  </div>
                </div>
              </button>
            ))
          )}
        </div>
      </Modal>
      <Modal open={banOpen} onClose={() => !banLoading && setBanOpen(false)} title="Заблокировать пользователя">
        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <div style={{ color: 'var(--text-muted)', fontSize: 14, lineHeight: 1.5 }}>
            Блокировка будет применена к @{user.username || user.id}. Оставьте срок пустым, если бан должен быть постоянным.
          </div>
          <Input
            label="Срок бана в днях"
            type="number"
            value={banDays}
            onChange={handleBanDaysChange}
            placeholder="Например, 7"
            min="1"
            max="3650"
            step="1"
            inputMode="numeric"
            pattern="[0-9]*"
            error={banError}
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

function formatProfileBanDate(value) {
  if (!value) return '';
  try {
    const normalized = typeof value === 'string' && !/[zZ]|[+-]\d{2}:\d{2}$/.test(value)
      ? value + 'Z'
      : value;
    const date = new Date(normalized);
    if (Number.isNaN(date.getTime())) return value;
    return date.toLocaleString('ru-RU', {
      day: 'numeric',
      month: 'long',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  } catch {
    return value;
  }
}

function pluralize(n, one, few, many) {
  const mod10 = n % 10, mod100 = n % 100;
  if (mod10 === 1 && mod100 !== 11) return one;
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 10 || mod100 >= 20)) return few;
  return many;
}

Object.assign(window, { ProfilePage, pluralize });
