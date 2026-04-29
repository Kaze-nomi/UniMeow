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
    window.__authFailed = () => { setCurrentUser(null); navigate('/login'); };
  }, []);


  React.useEffect(() => {
    const h = (e) => {
      const user = e.detail;
      setCurrentUser(user);
      if (user) {
        if (!user.username) navigate('/complete-registration');
        else navigate('/');
      }
    };
    window.addEventListener('mock-login', h);
    return () => window.removeEventListener('mock-login', h);
  }, []);


  React.useEffect(() => {
    API.gql(API.Q.me, {}, false)
      .then(d => {
        const user = d.me;
        setCurrentUser(user);
        if (!user.username && path !== '/complete-registration') {
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
    navigate('/login');
  };
  const openCompose = () => {
    if (!currentUser) { navigate('/login'); return; }
    setComposeOpen(true);
  };

  const segments = path.split('/').filter(Boolean);
  const route = segments[0] || '';
  const param = segments[1];

  const renderPage = () => {
    if (authLoading) {
      return (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', minHeight: '100vh' }}>
          <div style={{ textAlign: 'center' }}>
            <CatLogo size={64} />
            <div style={{ marginTop: 12 }}><Spinner size={28} /></div>
          </div>
        </div>
      );
    }

    if (route === 'login') return <LoginPage onNavigate={navigate} />;
    if (route === 'complete-registration') return <CompleteRegistrationPage onNavigate={navigate} onUserUpdated={handleUserUpdated} />;

    const universitySlug = route && !['feed', 'profile', 'post', 'settings', 'explore', 'about', 'admin', 'login', 'complete-registration'].includes(route)
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
          <SettingsPage currentUser={currentUser} onNavigate={navigate} />
        ) : route === 'explore' ? (
          <ExplorePage currentUser={currentUser} onNavigate={navigate} />
        ) : route === 'about' ? (
          <AboutPage onNavigate={navigate} />
        ) : route === 'admin' && param === 'suggestions' ? (
          <AdminPage currentUser={currentUser} onNavigate={navigate} view="suggestions" />
        ) : route === 'admin' && param === 'universities' ? (
          <AdminPage currentUser={currentUser} onNavigate={navigate} view="universities" />
        ) : (
          <EmptyState icon={<CatFaceIcon />} title="Страница не найдена" subtitle={<span onClick={() => navigate('/')} style={{ color: 'var(--accent)', cursor: 'pointer' }}>На главную →</span>} />
        )}
      </AppLayout>
    );
  };

  return (
    <>
      {renderPage()}
      <ComposeModal open={composeOpen} onClose={() => setComposeOpen(false)} currentUser={currentUser} onNavigate={navigate} onCreated={() => window.dispatchEvent(new CustomEvent('um-post-created'))} />
      <MockUserPicker onUserChange={handleUserUpdated} />
      <AppModals />
    </>
  );
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
