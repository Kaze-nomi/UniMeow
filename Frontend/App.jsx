function useRouter() {
  const getPath = () => {
    if (window.location.hash && window.location.hash.startsWith('#/')) {
      return window.location.hash.slice(1) || '/feed';
    }
    const path = window.location.pathname || '/';
    return path === '/' ? '/feed' : path;
  };
  const [path, setPath] = React.useState(getPath);
  const [history, setHistory] = React.useState([getPath()]);

  React.useEffect(() => {
    const onPop = () => setPath(getPath());
    window.addEventListener('popstate', onPop);
    if (window.location.hash && window.location.hash.startsWith('#/')) {
      const migrated = window.location.hash.slice(1) || '/feed';
      window.history.replaceState({}, '', migrated + window.location.search);
      setPath(migrated);
    } else if (window.location.pathname === '/') {
      window.history.replaceState({}, '', '/feed' + window.location.search);
    }
    return () => window.removeEventListener('popstate', onPop);
  }, []);

  const navigate = (to) => {
    if (to === -1) {
      if (history.length > 1) {
        const prev = history[history.length - 2];
        setHistory(h => h.slice(0, -1));
        window.history.pushState({}, '', prev);
        setPath(prev);
      } else {
        window.history.pushState({}, '', '/feed');
        setPath('/feed');
      }
      return;
    }
    const target = to === '/' ? '/feed' : to;
    setHistory(h => [...h, target]);
    window.history.pushState({}, '', target);
    setPath(target);
  };

  return { path, navigate };
}

function App() {
  const { path, navigate } = useRouter();
  const [currentUser, setCurrentUser] = React.useState(null);
  const [authLoading, setAuthLoading] = React.useState(true);
  const [composeOpen, setComposeOpen] = React.useState(false);

  React.useEffect(() => {
    window.__authFailed = () => {
      setCurrentUser(null);
      API.clearUserCache && API.clearUserCache();
      if (window.location.pathname === '/banned' || window.location.search.includes('banned=')) {
        return;
      }
      navigate('/login');
    };
  }, []);

  React.useEffect(() => {
    const h = () => {
      API.gql(API.Q.me, {}, true, 0)
        .then(d => {
          if (!d.me) return;
          setCurrentUser(d.me);
          API.invalidateUser && API.invalidateUser(d.me.id);
          navigate('/');
        })
        .catch(() => {});
    };
    window.addEventListener('um-account-banned', h);
    return () => window.removeEventListener('um-account-banned', h);
  }, []);


  React.useEffect(() => {
    const h = (e) => {
      const user = e.detail;
      setCurrentUser(user);
      if (!user) API.clearUserCache && API.clearUserCache();
      if (user) {
        if (user.isBanned) navigate('/');
        else if (!user.username) navigate('/complete-registration');
        else navigate('/');
      }
    };
    window.addEventListener('mock-login', h);
    return () => window.removeEventListener('mock-login', h);
  }, []);


  React.useEffect(() => {
    API.gql(API.Q.me)
      .then(d => {
        const user = d.me;
        if (!user) return;
        setCurrentUser(user);
        if (!user.isBanned && !user.username && path !== '/complete-registration') {
          navigate('/complete-registration');
        }
      })
      .catch(() => {})
      .finally(() => setAuthLoading(false));
  }, []);

  const handleUserUpdated = (user) => {
    setCurrentUser(user);
    API.invalidateUser && API.invalidateUser(user.id);
  };

  const handleLogout = () => {
    setCurrentUser(null);
    API.clearUserCache && API.clearUserCache();
    navigate('/login');
  };
  const handleBannedLogout = async () => {
    try { await API.logout(); } catch {}
    handleLogout();
  };
  const handleAccountDeleted = () => {
    setCurrentUser(null);
    API.clearUserCache && API.clearUserCache();
    navigate('/login');
  };

  React.useEffect(() => {
    if (currentUser?.isBanned) setComposeOpen(false);
  }, [currentUser?.isBanned]);

  const openCompose = () => {
    if (!currentUser) { navigate('/login'); return; }
    if (currentUser.isBanned) return;
    setComposeOpen(true);
  };

  const segments = path.split('/').filter(Boolean);
  const route = segments[0] || '';
  const param = segments[1];

  const renderPage = () => {
    if (route === 'banned') return <PermanentBanPage onNavigate={navigate} />;

    if (authLoading) {
      return (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', minHeight: '100vh' }}>
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 12 }}>
            <CatLogo size={64} />
            <Spinner size={28} />
          </div>
        </div>
      );
    }

    if (currentUser?.isBanned) return <BannedAccountPage user={currentUser} onLogout={handleBannedLogout} />;

    if (route === 'login') return <LoginPage onNavigate={navigate} />;
    if (route === 'complete-registration') return <CompleteRegistrationPage onNavigate={navigate} onUserUpdated={handleUserUpdated} />;

    const universitySlug = route && !['feed', 'profile', 'post', 'settings', 'explore', 'notifications', 'about', 'privacy', 'consent', 'admin', 'login', 'banned', 'complete-registration'].includes(route)
      ? route
      : null;

    return (
      <AppLayout currentUser={currentUser} onNavigate={navigate} currentPath={path} openCompose={openCompose} onLogout={handleLogout}>
        {route === '' || route === 'feed' || universitySlug ? (
          <FeedPage currentUser={currentUser} onNavigate={navigate} universitySlug={universitySlug} />
        ) : route === 'profile' && param ? (
          <ProfilePage userId={param} currentUser={currentUser} onNavigate={navigate} onUserUpdated={handleUserUpdated} />
        ) : route === 'post' && param ? (
          <PostPage postId={param} currentUser={currentUser} onNavigate={navigate} />
        ) : route === 'settings' ? (
          <SettingsPage currentUser={currentUser} onNavigate={navigate} onAccountDeleted={handleAccountDeleted} />
        ) : route === 'explore' ? (
          <ExplorePage currentUser={currentUser} onNavigate={navigate} />
        ) : route === 'notifications' ? (
          <NotificationsPage currentUser={currentUser} onNavigate={navigate} />
        ) : route === 'about' ? (
          <AboutPage onNavigate={navigate} />
        ) : route === 'privacy' ? (
          <PrivacyPage onNavigate={navigate} />
        ) : route === 'consent' ? (
          <ConsentPage onNavigate={navigate} />
        ) : route === 'admin' && param === 'suggestions' ? (
          <AdminPage currentUser={currentUser} onNavigate={navigate} view="suggestions" />
        ) : route === 'admin' && param === 'universities' ? (
          <AdminPage currentUser={currentUser} onNavigate={navigate} view="universities" />
        ) : (
          <EmptyState icon={<SearchIcon size={40} color="var(--text-muted)" />} title="Страница не найдена" subtitle={<span onClick={() => navigate('/')} style={{ color: 'var(--accent)', cursor: 'pointer' }}>На главную →</span>} />
        )}
      </AppLayout>
    );
  };

  return (
    <>
      {renderPage()}
      <ComposeModal open={composeOpen && !currentUser?.isBanned} onClose={() => setComposeOpen(false)} currentUser={currentUser} onNavigate={navigate} onCreated={(newPost) => { if (newPost) window.dispatchEvent(new CustomEvent('um-post-created', { detail: newPost })); }} />
      <MockUserPicker onUserChange={handleUserUpdated} />
      <AppModals currentUser={currentUser} />
    </>
  );
}

function PermanentBanPage({ onNavigate }) {
  const params = new URLSearchParams(window.location.search);
  const reason = params.get('reason')?.trim() || 'Причина не указана';

  return (
    <div style={{
      minHeight: '100vh',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      padding: 24,
      background: 'var(--bg)',
    }}>
      <div style={{
        width: '100%',
        maxWidth: 520,
        background: 'var(--surface)',
        border: '1px solid var(--border)',
        borderRadius: 16,
        padding: 24,
        boxShadow: 'var(--card-shadow)',
      }}>
        <div style={{
          width: 52,
          height: 52,
          borderRadius: '50%',
          background: 'var(--like-subtle)',
          color: 'var(--like)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          marginBottom: 16,
          fontWeight: 900,
          fontSize: 24,
        }}>!</div>
        <h1 style={{ margin: '0 0 10px', fontSize: 24, fontWeight: 900 }}>Аккаунт заблокирован</h1>
        <p style={{ margin: '0 0 18px', color: 'var(--text-muted)', fontSize: 15, lineHeight: 1.55 }}>
          Этот Google аккаунт заблокирован бессрочно. Войти или создать новый аккаунт с ним нельзя.
        </p>
        <div style={{ padding: '12px 14px', border: '1px solid var(--border)', borderRadius: 10, background: 'var(--surface-2)', marginBottom: 18 }}>
          <div style={{ fontSize: 12, color: 'var(--text-muted)', fontWeight: 700, textTransform: 'uppercase', marginBottom: 4 }}>
            Причина
          </div>
          <div style={{ fontSize: 14, lineHeight: 1.5, overflowWrap: 'anywhere' }}>
            {reason}
          </div>
        </div>
        <Button variant="secondary" onClick={() => onNavigate('/login')}>На страницу входа</Button>
      </div>
    </div>
  );
}

function BannedAccountPage({ user, onLogout }) {
  const reason = user?.banReason?.trim() || 'Причина не указана';
  const bannedUntil = formatBanDate(user?.bannedUntil);

  return (
    <div style={{
      minHeight: '100vh',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      padding: 24,
      background: 'var(--bg)',
    }}>
      <div style={{
        width: '100%',
        maxWidth: 520,
        background: 'var(--surface)',
        border: '1px solid var(--border)',
        borderRadius: 16,
        padding: 24,
        boxShadow: 'var(--card-shadow)',
      }}>
        <div style={{
          width: 52,
          height: 52,
          borderRadius: '50%',
          background: 'var(--like-subtle)',
          color: 'var(--like)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          marginBottom: 16,
        }}>
          <ShieldIcon size={26} />
        </div>
        <h1 style={{ margin: '0 0 10px', fontSize: 26, lineHeight: 1.2, fontWeight: 850 }}>
          Аккаунт заблокирован
        </h1>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12, color: 'var(--text)', fontSize: 15, lineHeight: 1.55 }}>
          <div>
            <div style={{ fontSize: 12, color: 'var(--text-muted)', fontWeight: 700, textTransform: 'uppercase' }}>
              Причина
            </div>
            <div>{reason}</div>
          </div>
          <div>
            <div style={{ fontSize: 12, color: 'var(--text-muted)', fontWeight: 700, textTransform: 'uppercase' }}>
              Срок
            </div>
            <div>{bannedUntil ? `Разблокировка: ${bannedUntil}` : 'Блокировка бессрочная'}</div>
          </div>
        </div>
        <div style={{ marginTop: 22, display: 'flex', justifyContent: 'flex-end' }}>
          <Button variant="secondary" onClick={onLogout}>Выйти</Button>
        </div>
      </div>
    </div>
  );
}

function formatBanDate(value) {
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

function MockUserPicker({ onUserChange }) {
  const [open, setOpen] = React.useState(false);

  React.useEffect(() => {
    const h = () => setOpen(true);
    window.addEventListener('mock-show-login', h);
    return () => window.removeEventListener('mock-show-login', h);
  }, []);

  const login = (userId) => {
    window.MOCK.login(userId);
    setOpen(false);
  };

  const logout = () => {
    window.MOCK.logout();
    setOpen(false);
  };

  if (!window.MOCK?.enabled) return null;

  return (
    <Modal open={open} onClose={() => setOpen(false)} title="Выберите персонажа" width={440}>
      <p style={{ fontSize: 13, color: 'var(--text-muted)', marginBottom: 16, marginTop: -8 }}>
        Каждый пользователь имеет разную роль и статус верификации
      </p>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        {window.MOCK.users.map(u => (
          <button key={u.id} onClick={() => login(u.id)} style={{
            display: 'flex', alignItems: 'center', gap: 12, padding: '12px 14px',
            borderRadius: 12, border: '1.5px solid var(--border)', background: 'var(--surface)',
            cursor: 'pointer', textAlign: 'left', fontFamily: 'inherit',
          }}>
            <Avatar user={u} size={42} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontWeight: 700, fontSize: 14, display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
                {u.name} {u.surname}
                {u.isStudentVerified && <Badge color="accent"><CheckIcon size={9} /> Студент</Badge>}
                {u.isEmployeeVerified && <Badge color="green"><ShieldIcon size={9} /> Сотрудник</Badge>}
              </div>
              <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>
                @{u.username} · {u.university?.name}{u.course ? ` · ${u.course} курс` : ''}
              </div>
              {u.bio && <div style={{ fontSize: 12, color: 'var(--text)', marginTop: 2, overflow: 'hidden', whiteSpace: 'nowrap', textOverflow: 'ellipsis' }}>{u.bio}</div>}
            </div>
            <span style={{ color: 'var(--accent)', fontSize: 18, flexShrink: 0 }}>→</span>
          </button>
        ))}
        <button onClick={logout} style={{
          padding: '10px 14px', borderRadius: 12, border: '1.5px dashed var(--border)',
          background: 'transparent', color: 'var(--text-muted)', fontFamily: 'inherit',
          fontSize: 14, cursor: 'pointer',
        }}>
          Продолжить без входа (анонимно)
        </button>
      </div>
    </Modal>
  );
}

Object.assign(window, { App });
