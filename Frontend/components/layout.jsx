


function AppLayout({ children, currentUser, onNavigate, currentPath, openCompose, onLogout }) {
  const [size, setSize] = React.useState({ w: window.innerWidth });
  React.useEffect(() => {
    const h = () => setSize({ w: window.innerWidth });
    window.addEventListener('resize', h);
    return () => window.removeEventListener('resize', h);
  }, []);
  const mobile = size.w < 420;
  const showRightRail = size.w >= 1100;


  const sideW = size.w >= 1280 ? 240 : 88;
  const collapsedSide = size.w < 1280;
  const mainW = 600;
  const rightW = 350;

  return (
    <div style={{ minHeight: '100vh', background: 'var(--bg)', color: 'var(--text)' }}>
      <div style={{
        maxWidth: showRightRail ? (sideW + mainW + rightW + 40) : (sideW + mainW + 16),
        margin: '0 auto', display: 'flex',
      }}>
        {!mobile && (
          <Sidebar
            currentUser={currentUser}
            onNavigate={onNavigate}
            currentPath={currentPath}
            collapsed={collapsedSide}
            width={sideW}
            openCompose={openCompose}
            onLogout={onLogout}
          />
        )}
        <main style={{
          flex: '1 1 auto',
          maxWidth: mobile ? '100%' : mainW,
          minHeight: '100vh',
          borderLeft: mobile ? 'none' : '1px solid var(--border)',
          borderRight: (mobile || !showRightRail) ? 'none' : '1px solid var(--border)',
          paddingBottom: mobile ? 72 : 0,
        }}>
          {children}
        </main>
        {showRightRail && !mobile && (
          <RightRail width={rightW} currentUser={currentUser} onNavigate={onNavigate} />
        )}
      </div>
      {mobile && <BottomNav currentUser={currentUser} onNavigate={onNavigate} currentPath={currentPath} openCompose={openCompose} />}
    </div>
  );
}

function useUnreadNotificationsCount(currentUser) {
  const [unreadCount, setUnreadCount] = React.useState(0);
  React.useEffect(() => {
    if (!currentUser) { setUnreadCount(0); return; }
    const load = () => API.gql(API.Q.getUnreadNotificationCount).then(d => setUnreadCount(d.getUnreadNotificationCount || 0)).catch(() => {});
    load();
    const id = setInterval(load, 60000);
    const h = () => setUnreadCount(0);
    window.addEventListener('um-notifications-marked-read', h);
    return () => { clearInterval(id); window.removeEventListener('um-notifications-marked-read', h); };
  }, [currentUser]);
  return unreadCount;
}

function Sidebar({ currentUser, onNavigate, currentPath, collapsed, width, openCompose, onLogout }) {
  const [accountMenuOpen, setAccountMenuOpen] = React.useState(false);
  const accountMenuRef = React.useRef(null);
  React.useEffect(() => {
    if (!accountMenuOpen) return;
    const h = (e) => { if (accountMenuRef.current && !accountMenuRef.current.contains(e.target)) setAccountMenuOpen(false); };
    document.addEventListener('mousedown', h);
    return () => document.removeEventListener('mousedown', h);
  }, [accountMenuOpen]);
  const unreadCount = useUnreadNotificationsCount(currentUser);

  const mainNav = [
    { path: '/feed', label: 'Главная', icon: HomeIcon },
    { path: '/explore', label: 'Поиск', icon: SearchIcon },
    ...(currentUser ? [
      { path: API.profileUrl(currentUser), label: 'Профиль', icon: UserIcon },
      { path: '/notifications', label: 'Уведомления', icon: BellIcon, badge: unreadCount },
    ] : []),
    ...(currentUser?.isAdmin ? [
      { path: '/admin/suggestions', label: 'Предложения', icon: StarIcon },
      { path: '/admin/universities', label: 'ВУЗы', icon: UniIcon },
    ] : []),
    { path: '/settings', label: 'Настройки', icon: GearIcon },
  ];

  const NavBtn = ({ path, label, Icon, badge }) => {
    const active = path === '/feed'
      ? (currentPath === '/feed' || currentPath === '/' || !['/explore', '/profile', '/post', '/settings', '/admin', '/login', '/notifications'].some(prefix => currentPath.startsWith(prefix)))
      : currentPath.startsWith(path);
    return (
      <button
        className="um-side-nav"
        onClick={() => onNavigate(path)}
        title={collapsed ? label : undefined}
        style={{
          display: 'flex', alignItems: 'center', gap: 18,
          width: collapsed ? 50 : 'auto',
          height: 50,
          padding: collapsed ? 0 : '0 22px 0 14px',
          justifyContent: collapsed ? 'center' : 'flex-start',
          borderRadius: 9999, border: 'none',
          background: 'transparent',
          color: active ? 'var(--accent)' : 'var(--text)',
          fontFamily: 'inherit', fontSize: 19, fontWeight: active ? 700 : 400,
          cursor: 'pointer', textAlign: 'left',
          position: 'relative',
        }}>
        <span style={{ position: 'relative', display: 'inline-flex' }}>
          <Icon size={26} color={active ? 'var(--accent)' : 'var(--text)'} filled={active} />
          {badge > 0 && (
            <span style={{
              position: 'absolute', top: -4, right: -6,
              background: 'var(--accent)', color: '#fff',
              borderRadius: 9999, fontSize: 10, fontWeight: 800,
              minWidth: 16, height: 16, padding: '0 3px',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              lineHeight: 1,
            }}>{badge > 99 ? '99+' : badge}</span>
          )}
        </span>
        {!collapsed && label}
      </button>
    );
  };

  const accountClick = () => {
    if (currentUser) onNavigate(API.profileUrl(currentUser));
    else onNavigate('/login');
  };
  const logout = async (e) => {
    e.stopPropagation();
    if (window.MOCK?.enabled) window.MOCK.logout();
    else await API.logout();
    setAccountMenuOpen(false);
    onLogout && onLogout();
  };

  return (
    <aside style={{
      position: 'sticky', top: 0, alignSelf: 'flex-start',
      height: '100vh', width,
      display: 'flex', flexDirection: 'column',
      padding: collapsed ? '4px 14px 12px 6px' : '4px 12px 12px',
      flexShrink: 0,
      alignItems: collapsed ? 'center' : 'stretch',
    }}>

      <button onClick={() => onNavigate('/feed')} style={{
        display: 'flex', alignItems: 'center', gap: 8,
        background: 'none', border: 'none', cursor: 'pointer',
        padding: collapsed ? 10 : '10px 12px', borderRadius: 9999,
        color: 'var(--text)', alignSelf: collapsed ? 'center' : 'flex-start',
      }}>
        <CatLogo size={collapsed ? 31 : 34} />
        {!collapsed && <span style={{ fontWeight: 800, fontSize: 17, letterSpacing: '-0.45px' }}>UniMeow</span>}
      </button>


      <nav style={{ display: 'flex', flexDirection: 'column', gap: 2, marginTop: 4, alignItems: collapsed ? 'center' : 'stretch', width: '100%' }}>
        {mainNav.map(item => <NavBtn key={item.path} path={item.path} label={item.label} Icon={item.icon} badge={item.badge || 0} />)}
      </nav>


      {currentUser && (
        <button
          onClick={openCompose}
          title={collapsed ? 'Запись' : undefined}
          style={{
            marginTop: 18,
            width: collapsed ? 52 : '90%',
            height: 52,
            borderRadius: 9999, border: 'none',
            background: 'var(--accent)', color: '#fff',
            fontFamily: 'inherit', fontSize: 17, fontWeight: 700,
            cursor: 'pointer',
            display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
            boxShadow: '0 1px 0 rgba(0,0,0,0.04)',
            alignSelf: collapsed ? 'center' : 'flex-start',
          }}>
          {collapsed
            ? <FeatherIcon size={24} color="#fff" />
            : 'Запись'}
        </button>
      )}


      <div style={{ flex: 1 }} />


      {currentUser ? (
        <div ref={accountMenuRef} style={{ position: 'relative', alignSelf: collapsed ? 'center' : 'stretch' }}>
        <button
          onClick={accountClick}
          className="um-side-nav"
          style={{
            display: 'flex', alignItems: 'center',
            gap: collapsed ? 0 : 12,
            padding: collapsed ? 6 : 10,
            borderRadius: 9999,
            border: 'none', background: 'transparent', cursor: 'pointer',
            textAlign: 'left',
            width: collapsed ? 52 : '100%',
            justifyContent: collapsed ? 'center' : 'flex-start',
          }}>
          <Avatar user={currentUser} size={40} />
          {!collapsed && (
            <div style={{ flex: 1, minWidth: 0, overflow: 'hidden' }}>
              <div style={{ fontWeight: 700, fontSize: 14.5, color: 'var(--text)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                {currentUser.name || currentUser.username}
              </div>
              <div style={{ fontSize: 13.5, color: 'var(--text-muted)' }}>@{currentUser.username}</div>
            </div>
          )}
          {!collapsed && (
            <span onClick={e => { e.stopPropagation(); setAccountMenuOpen(v => !v); }} style={{ display: 'inline-flex', padding: 6, borderRadius: 9999 }}>
              <DotsIcon size={16} color="var(--text-muted)" />
            </span>
          )}
        </button>
        {accountMenuOpen && !collapsed && (
          <div style={{
            position: 'absolute', left: 0, right: 0, bottom: 58,
            background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 14,
            boxShadow: '0 10px 30px rgba(0,0,0,0.18)', padding: 6, zIndex: 30,
          }}>
            <button onClick={logout} style={{
              width: '100%', padding: '11px 12px', borderRadius: 10,
              border: 'none', background: 'transparent', color: 'var(--like)',
              fontFamily: 'inherit', fontSize: 14, fontWeight: 700, textAlign: 'left', cursor: 'pointer',
            }}>Выйти из аккаунта</button>
          </div>
        )}
        </div>
      ) : (
        !collapsed && (
          <Button onClick={() => onNavigate('/login')} full>Войти</Button>
        )
      )}
    </aside>
  );
}

function BottomNav({ currentUser, onNavigate, currentPath, openCompose }) {
  const unreadCount = useUnreadNotificationsCount(currentUser);
  const items = [
    { path: '/feed', label: 'Главная', Icon: HomeIcon },
    { path: '/explore', label: 'Поиск', Icon: SearchIcon },
    ...(currentUser ? [
      { path: '/notifications', label: 'Увед.', Icon: BellIcon, badge: unreadCount },
      { path: API.profileUrl(currentUser), label: 'Профиль', Icon: UserIcon },
    ] : []),
    { path: '/settings', label: 'Ещё', Icon: GearIcon },
  ];
  return (
    <>
      <nav style={{
        position: 'fixed', bottom: 0, left: 0, right: 0, height: 56,
        background: 'var(--bg)',
        borderTop: '1px solid var(--border)',
        display: 'flex', alignItems: 'center', justifyContent: 'space-around',
        zIndex: 100, paddingBottom: 'env(safe-area-inset-bottom)',
      }}>
        {items.map(item => {
          const active = item.path === '/feed' ? (currentPath === '/feed' || currentPath === '/') : currentPath.startsWith(item.path);
          return (
            <button key={item.path} onClick={() => onNavigate(item.path)} style={{
              display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 2,
              border: 'none', background: 'none',
              color: active ? 'var(--accent)' : 'var(--text-muted)',
              fontSize: 10, fontWeight: active ? 650 : 500, fontFamily: 'inherit',
              cursor: 'pointer', padding: '8px 4px',
              flex: '1 1 0',
              minWidth: 0,
            }}>
              <span style={{ position: 'relative', display: 'inline-flex' }}>
                <item.Icon size={24} color={active ? 'var(--accent)' : 'var(--text-muted)'} filled={active} />
                {item.badge > 0 && (
                  <span style={{
                    position: 'absolute', top: -5, right: -8,
                    background: 'var(--accent)', color: '#fff',
                    borderRadius: 9999, fontSize: 9, fontWeight: 800,
                    minWidth: 15, height: 15, padding: '0 3px',
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    lineHeight: 1,
                  }}>{item.badge > 99 ? '99+' : item.badge}</span>
                )}
              </span>
              <span style={{ maxWidth: 58, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{item.label}</span>
            </button>
          );
        })}
      </nav>
      {currentUser && openCompose && (
        <button onClick={openCompose} aria-label="Запись" style={{
          position: 'fixed', right: 18, bottom: 76,
          width: 56, height: 56, borderRadius: '50%',
          background: 'var(--accent)', border: 'none', color: '#fff',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          boxShadow: '0 8px 24px var(--accent-glow)',
          zIndex: 101, cursor: 'pointer',
        }}>
          <FeatherIcon size={24} color="#fff" />
        </button>
      )}
    </>
  );
}

function RightRail({ width, currentUser, onNavigate }) {
  const [unis, setUnis] = React.useState([]);
  const [query, setQuery] = React.useState('');
  const [result, setResult] = React.useState(null);
  const [searchLoading, setSearchLoading] = React.useState(false);
  const [searchError, setSearchError] = React.useState('');

  React.useEffect(() => {
    API.gql(API.Q.listUniversities).then(d => setUnis(d.listUniversities || [])).catch(() => {});
  }, [currentUser]);

  React.useEffect(() => {
    if (!query.trim()) {
      setResult(null);
      setSearchError('');
    }
  }, [query]);

  const searchRightRail = async () => {
    const q = query.trim().replace(/^@/, '');
    if (!q) return;
    setSearchLoading(true);
    setSearchError('');
    setResult(null);
    try {
      const d = await API.gql(API.Q.getUserByUsername, { username: q });
      if (d.getUserByUsername) setResult(d.getUserByUsername);
      else setSearchError('Пользователь не найден');
    } catch {
      setSearchError('Пользователь не найден');
    } finally {
      setSearchLoading(false);
    }
  };

  return (
    <aside style={{
      width, flexShrink: 0,
      padding: '12px 16px 24px',
      position: 'sticky', top: 0, alignSelf: 'flex-start',
      height: '100vh', overflowY: 'auto',
    }}>

      <div style={{ position: 'sticky', top: 0, background: 'var(--bg)', paddingBottom: 12, paddingTop: 4, zIndex: 1 }}>
        <div style={{ position: 'relative' }}>
          <SearchIcon size={18} color="var(--text-muted)" style={{ position: 'absolute', left: 14, top: '50%', transform: 'translateY(-50%)' }} />
          <input
            value={query}
            placeholder="Поиск"
            onChange={e => setQuery(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter') searchRightRail(); }}
            style={{
              width: '100%', padding: '11px 14px 11px 44px', borderRadius: 9999,
              border: '1px solid transparent', background: 'var(--surface-2)',
              color: 'var(--text)', fontSize: 15, outline: 'none',
            }}
          />
        </div>
        {(query.trim() || result || searchError) && (
          <div style={{ marginTop: 8, background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 14, overflow: 'hidden' }}>
            {searchLoading ? (
              <div style={{ padding: '12px 14px', color: 'var(--text-muted)', fontSize: 13 }}>Поиск...</div>
            ) : result ? (
              <button
                onClick={() => onNavigate(API.profileUrl(result))}
                className="um-side-nav"
                style={{ display: 'flex', alignItems: 'center', gap: 10, width: '100%', padding: 12, border: 'none', background: 'transparent', cursor: 'pointer', textAlign: 'left', fontFamily: 'inherit' }}
              >
                <Avatar user={result} size={36} />
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontWeight: 700, fontSize: 14, color: 'var(--text)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {(result.name && result.surname) ? `${result.name} ${result.surname}` : result.name || result.username}
                  </div>
                  <div style={{ fontSize: 12.5, color: 'var(--text-muted)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>@{result.username}</div>
                </div>
              </button>
            ) : searchError ? (
              <div style={{ padding: '12px 14px', color: 'var(--text-muted)', fontSize: 13 }}>{searchError}</div>
            ) : (
              <button onClick={searchRightRail} style={{ width: '100%', padding: '10px 14px', border: 'none', background: 'transparent', color: 'var(--accent)', fontFamily: 'inherit', fontWeight: 700, cursor: 'pointer', textAlign: 'left' }}>
                Найти @{query.trim().replace(/^@/, '')}
              </button>
            )}
          </div>
        )}
      </div>


      <section style={{ background: 'var(--surface-2)', borderRadius: 16, padding: '12px 4px', marginBottom: 16 }}>
        <h3 style={{ margin: '4px 14px 8px', fontSize: 19, fontWeight: 800 }}>Ленты</h3>
        <button onClick={() => onNavigate('/outside/')}
          style={{
            display: 'flex', alignItems: 'center', gap: 12, width: '100%',
            padding: '10px 16px', border: 'none', background: 'transparent', cursor: 'pointer',
            textAlign: 'left', fontFamily: 'inherit', transition: 'background 0.12s',
          }}
          onMouseEnter={e => e.currentTarget.style.background = 'var(--surface-hover)'}
          onMouseLeave={e => e.currentTarget.style.background = 'transparent'}>
          <div style={{
            width: 36, height: 36, borderRadius: 10,
            background: 'var(--accent-subtle)', color: 'var(--accent)',
            display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0,
          }}>
            <MessageBubbleIcon size={21} color="currentColor" />
          </div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontWeight: 700, fontSize: 14.5, color: 'var(--text)' }}>Без вуза</div>
            <div style={{ fontSize: 13, color: 'var(--text-muted)' }}>Выбор, поступление, общие вопросы</div>
          </div>
        </button>
        <div style={{ height: 1, background: 'var(--border)', margin: '6px 12px' }} />
        <h4 style={{ margin: '8px 14px 4px', fontSize: 12, fontWeight: 800, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: 0.5 }}>Университеты</h4>
        {unis.map(u => (
          <button key={u.id} onClick={() => onNavigate('/' + API.universitySlug(u) + '/')}
            style={{
              display: 'flex', alignItems: 'center', gap: 12, width: '100%',
              padding: '10px 16px', border: 'none', background: 'transparent', cursor: 'pointer',
              textAlign: 'left', fontFamily: 'inherit', transition: 'background 0.12s',
            }}
            onMouseEnter={e => e.currentTarget.style.background = 'var(--surface-hover)'}
            onMouseLeave={e => e.currentTarget.style.background = 'transparent'}>
            <UniBadge uni={u} size={36} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontWeight: 700, fontSize: 14.5, color: 'var(--text)' }}>{u.name}</div>
              <div style={{ fontSize: 13, color: 'var(--text-muted)' }}>{u.shortName}</div>
            </div>
          </button>
        ))}
      </section>


      <div style={{ marginTop: 16, padding: '0 14px', fontSize: 12, color: 'var(--text-muted)', lineHeight: 1.6 }}>
        UniMeow<br />
        <span style={{ cursor: 'pointer' }} onClick={() => onNavigate('/about')}>О проекте</span> ·{' '}
        <span style={{ cursor: 'pointer' }} onClick={() => window.dispatchEvent(new CustomEvent('open-modal', { detail: 'suggest' }))}>Обратная связь</span> ·{' '}
        <span style={{ cursor: 'pointer' }} onClick={() => window.dispatchEvent(new CustomEvent('open-modal', { detail: 'add-uni' }))}>Добавить свой ВУЗ</span> ·{' '}
        <span style={{ cursor: 'pointer' }} onClick={() => window.dispatchEvent(new CustomEvent('open-modal', { detail: 'add-faculty' }))}>Добавить свой факультет</span> ·{' '}
        <span style={{ cursor: 'pointer' }} onClick={() => window.dispatchEvent(new CustomEvent('open-modal', { detail: 'add-program' }))}>Добавить свою программу</span>
      </div>
    </aside>
  );
}

function UniBadge({ uni, size = 36 }) {
  const initial = (uni?.shortName || uni?.name || '?').charAt(0);
  const iconUrl = API.resolveAssetUrl(uni?.iconUrl);
  return (
    <div style={{
      width: size, height: size, borderRadius: 10,
      background: iconUrl ? 'transparent' : (uni?.color || 'var(--accent)'),
      color: '#fff', display: 'flex', alignItems: 'center', justifyContent: 'center',
      fontWeight: 800, fontSize: size * 0.42, flexShrink: 0,
      overflow: 'hidden',
    }}>
      {iconUrl
        ? <img src={iconUrl} alt="" style={{ width: '100%', height: '100%', objectFit: 'contain', display: 'block' }} />
        : initial}
    </div>
  );
}

function ThemeToggle() {
  const [dark, setDark] = React.useState(() => (window.UM_THEME?.getResolvedTheme?.()
    || document.documentElement.dataset.theme) === 'dark');
  React.useEffect(() => {
    const h = (event) => setDark((event.detail?.theme || window.UM_THEME?.getResolvedTheme?.()) === 'dark');
    window.addEventListener('um-theme-change', h);
    return () => window.removeEventListener('um-theme-change', h);
  }, []);
  const toggle = () => {
    const next = !dark;
    setDark(next);
    if (window.UM_THEME?.apply) {
      window.UM_THEME.apply(next ? 'dark' : 'light');
    } else {
      document.documentElement.dataset.theme = next ? 'dark' : 'light';
      localStorage.setItem('um-theme', next ? 'dark' : 'light');
    }
  };
  return (
    <button onClick={toggle} title="Сменить тему" style={{
      background: 'none', border: 'none', borderRadius: 8, padding: '6px 8px',
      cursor: 'pointer', color: 'var(--text-muted)', fontSize: 15, lineHeight: 1,
      display: 'flex', alignItems: 'center', gap: 4,
    }}>
      {dark ? <svg width={16} height={16} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}><circle cx="12" cy="12" r="4"/><path d="M12 2v3M12 19v3M4.2 4.2l2.1 2.1M17.7 17.7l2.1 2.1M2 12h3M19 12h3M4.2 19.8l2.1-2.1M17.7 6.3l2.1-2.1"/></svg> : <svg width={16} height={16} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}><path d="M21 13.5A8.5 8.5 0 1 1 10.5 3 7 7 0 0 0 21 13.5Z"/></svg>}
    </button>
  );
}

function CatLogo({ size = 32 }) {
  return (
    <img src="/logo.png" alt="UniMeow" style={{ width: size, height: size, flexShrink: 0, objectFit: 'contain' }} />
  );
}


function TeacherIcon({ size = 20, color = 'currentColor' }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
      <path d="M22 10v6M2 10l10-5 10 5-10 5z"/>
      <path d="M6 12v5c3 3 9 3 12 0v-5"/>
    </svg>
  );
}
function HomeIcon({ size = 20, color = 'currentColor', filled }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill={filled ? color : 'none'} stroke={color} strokeWidth={filled ? 0 : 2} strokeLinecap="round" strokeLinejoin="round"><path d="M3 9l9-7 9 7v11a2 2 0 01-2 2H5a2 2 0 01-2-2z"/></svg>;
}
function SearchIcon({ size = 20, color = 'currentColor', filled, style }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth={filled ? 2.5 : 2} strokeLinecap="round" strokeLinejoin="round" style={style}><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>;
}
function UserIcon({ size = 20, color = 'currentColor', filled }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill={filled ? color : 'none'} stroke={color} strokeWidth={filled ? 0 : 2} strokeLinecap="round" strokeLinejoin="round"><path d="M20 21v-2a4 4 0 00-4-4H8a4 4 0 00-4 4v2"/><circle cx="12" cy="7" r="4" fill={filled ? color : 'none'}/></svg>;
}
function GearIcon({ size = 20, color = 'currentColor', filled }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth={filled ? 2.5 : 2} strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 00.33 1.82l.06.06a2 2 0 010 2.83 2 2 0 01-2.83 0l-.06-.06a1.65 1.65 0 00-1.82-.33 1.65 1.65 0 00-1 1.51V21a2 2 0 01-4 0v-.09A1.65 1.65 0 009 19.4a1.65 1.65 0 00-1.82.33l-.06.06a2 2 0 01-2.83-2.83l.06-.06A1.65 1.65 0 004.68 15a1.65 1.65 0 00-1.51-1H3a2 2 0 010-4h.09A1.65 1.65 0 004.6 9a1.65 1.65 0 00-.33-1.82l-.06-.06a2 2 0 012.83-2.83l.06.06A1.65 1.65 0 009 4.68a1.65 1.65 0 001-1.51V3a2 2 0 014 0v.09a1.65 1.65 0 001 1.51 1.65 1.65 0 001.82-.33l.06-.06a2 2 0 012.83 2.83l-.06.06A1.65 1.65 0 0019.4 9a1.65 1.65 0 001.51 1H21a2 2 0 010 4h-.09a1.65 1.65 0 00-1.51 1z"/></svg>;
}
function FeatherIcon({ size = 20, color = 'currentColor' }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M20.24 12.24a6 6 0 00-8.49-8.49L5 10.5V19h8.5z"/><line x1="16" y1="8" x2="2" y2="22"/><line x1="17.5" y1="15" x2="9" y2="15"/></svg>;
}
function ChevronDownIcon({ size = 16, color = 'currentColor' }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><polyline points="6 9 12 15 18 9"/></svg>;
}
function BackArrowIcon({ size = 20, color = 'currentColor' }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><line x1="19" y1="12" x2="5" y2="12"/><polyline points="12 19 5 12 12 5"/></svg>;
}
function HeartIcon({ size = 18, color = 'currentColor', filled }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill={filled ? color : 'none'} stroke={color} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M20.84 4.61a5.5 5.5 0 00-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 00-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 000-7.78z"/></svg>;
}
function CommentIcon({ size = 18, color = 'currentColor' }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2z"/></svg>;
}
function ShareIcon({ size = 18, color = 'currentColor' }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><line x1="8.59" y1="13.51" x2="15.42" y2="17.49"/><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"/></svg>;
}
function PlusIcon({ size = 18, color = 'currentColor' }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth="2.5" strokeLinecap="round"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>;
}
function TrashIcon({ size = 16, color = 'currentColor' }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14H6L5 6"/><path d="M10 11v6"/><path d="M14 11v6"/><path d="M9 6V4h6v2"/></svg>;
}
function EditIcon({ size = 16, color = 'currentColor' }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M11 4H4a2 2 0 00-2 2v14a2 2 0 002 2h14a2 2 0 002-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 013 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>;
}
function CheckIcon({ size = 16, color = 'currentColor' }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"><polyline points="20 6 9 17 4 12"/></svg>;
}
function ShieldIcon({ size = 16, color = 'currentColor' }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>;
}

function DotsIcon({ size = 20, color = 'currentColor' }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="5" r="1" fill={color} stroke="none"/><circle cx="12" cy="12" r="1" fill={color} stroke="none"/><circle cx="12" cy="19" r="1" fill={color} stroke="none"/></svg>;
}
function InfoIcon({ size = 16, color = 'currentColor' }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>;
}
function UniIcon({ size = 16, color = 'currentColor' }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M3 9l9-7 9 7v11a2 2 0 01-2 2H5a2 2 0 01-2-2z"/><polyline points="9 22 9 12 15 12 15 22"/></svg>;
}
function StarIcon({ size = 16, color = 'currentColor' }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/></svg>;
}
function BellIcon({ size = 20, color = 'currentColor', filled }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill={filled ? color : 'none'} stroke={color} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M18 8A6 6 0 006 8c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.73 21a2 2 0 01-3.46 0"/></svg>;
}

function TwitterModal({ open, onClose, title, width = 520, children }) {
  if (!open) return null;
  return (
    <div onClick={onClose} style={{
      position: 'fixed', inset: 0, background: 'rgba(91,112,131,0.4)',
      backdropFilter: 'blur(4px)', zIndex: 1000,
      display: 'flex', alignItems: 'flex-start', justifyContent: 'center', padding: '40px 16px',
    }}>
      <div onClick={e => e.stopPropagation()} style={{
        background: 'var(--bg)', borderRadius: 16, width: '100%', maxWidth: width,
        maxHeight: '90vh', overflow: 'auto', boxShadow: '0 0 30px rgba(0,0,0,0.15)',
      }}>
        <div style={{
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          padding: '8px 16px', borderBottom: '1px solid var(--border)',
          position: 'sticky', top: 0, background: 'var(--header-bg)',
          backdropFilter: 'saturate(180%) blur(12px)', WebkitBackdropFilter: 'saturate(180%) blur(12px)',
        }}>
          <h3 style={{ margin: 0, fontSize: 18, fontWeight: 800 }}>{title}</h3>
          <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text)', fontSize: 22, lineHeight: 1, padding: 6 }}>×</button>
        </div>
        <div style={{ padding: 16 }}>{children}</div>
      </div>
    </div>
  );
}


function AppModals() {
  const [modal, setModal] = React.useState(null);
  const [modalPayload, setModalPayload] = React.useState(null);
  const [uniForm, setUniForm] = React.useState({
    name: '', shortName: '', subdomain: '', studentDomain: '', employeeDomain: '', iconUrl: ''
  });
  const [facultyForm, setFacultyForm] = React.useState({
    universityId: '', facultyName: '', shortName: ''
  });
  const [programForm, setProgramForm] = React.useState({
    facultyId: '', name: '', shortName: ''
  });
  const [universities, setUniversities] = React.useState([]);
  const [faculties, setFaculties] = React.useState([]);
  const [suggestion, setSuggestion] = React.useState('');
  const [submitted, setSubmitted] = React.useState(false);
  const [error, setError] = React.useState('');
  const [loading, setLoading] = React.useState(false);

  React.useEffect(() => {
    const h = (e) => {
      const detail = e.detail;
      if (detail && typeof detail === 'object') {
        setModal(detail.type);
        setModalPayload(detail);
        if (detail.facultyId) setProgramForm(f => ({ ...f, facultyId: detail.facultyId }));
      } else {
        setModal(detail);
        setModalPayload(null);
      }
    };
    window.addEventListener('open-modal', h);
    return () => window.removeEventListener('open-modal', h);
  }, []);
  React.useEffect(() => {
    if (modal !== 'add-faculty' && modal !== 'add-program') return;
    API.gql(API.Q.listUniversities).then(d => setUniversities(d.listUniversities || [])).catch(() => setUniversities([]));
  }, [modal]);
  React.useEffect(() => {
    if (modal !== 'add-program') return;
    const universityId = modalPayload?.universityId || universities[0]?.id;
    if (!universityId) return;
    API.gql(API.Q.listFaculties, { universityId }).then(d => setFaculties(d.listFaculties || [])).catch(() => setFaculties([]));
  }, [modal, modalPayload, universities]);

  const close = () => { setModal(null); setModalPayload(null); setSubmitted(false); setError(''); setLoading(false); };
  const submitUniversity = async () => {
    setLoading(true); setError('');
    try {
      await API.gql(API.M.createUniversityProposal, {
        input: {
          name: uniForm.name.trim(),
          shortName: uniForm.shortName.trim(),
          subdomain: uniForm.subdomain.trim().toLowerCase(),
          studentDomain: uniForm.studentDomain.trim(),
          employeeDomain: uniForm.employeeDomain.trim(),
          city: null,
          description: null,
          iconUrl: uniForm.iconUrl || null
        },
        clientRequestId: API.newClientRequestId(),
      });
      setSubmitted(true);
      window.dispatchEvent(new CustomEvent('um-admin-refresh'));
    } catch (e) {
      setError(e.isUnauth ? 'Войдите, чтобы отправить заявку' : e.message);
    } finally { setLoading(false); }
  };
  const submitSuggestion = async () => {
    setLoading(true); setError('');
    try {
      await API.gql(API.M.createImprovementSuggestion, { text: suggestion.trim(), clientRequestId: API.newClientRequestId() });
      setSubmitted(true);
      window.dispatchEvent(new CustomEvent('um-admin-refresh'));
    } catch (e) {
      setError(e.isUnauth ? 'Войдите, чтобы отправить предложение' : e.message);
    } finally { setLoading(false); }
  };
  const submitFaculty = async () => {
    setLoading(true); setError('');
    try {
      await API.gql(API.M.createFacultyProposal, {
        input: {
          universityId: facultyForm.universityId,
          name: facultyForm.facultyName.trim(),
          shortName: facultyForm.shortName.trim()
        },
        clientRequestId: API.newClientRequestId(),
      });
      setSubmitted(true);
      window.dispatchEvent(new CustomEvent('um-admin-refresh'));
    } catch (e) {
      setError(e.isUnauth ? 'Войдите, чтобы отправить заявку' : e.message);
    } finally { setLoading(false); }
  };
  const submitProgram = async () => {
    setLoading(true); setError('');
    try {
      await API.gql(API.M.createProgramProposal, {
        input: {
          facultyId: programForm.facultyId,
          name: programForm.name.trim(),
          shortName: programForm.shortName.trim()
        },
        clientRequestId: API.newClientRequestId(),
      });
      setSubmitted(true);
      window.dispatchEvent(new CustomEvent('um-admin-refresh'));
    } catch (e) {
      setError(e.isUnauth ? 'Войдите, чтобы отправить заявку' : e.message);
    } finally { setLoading(false); }
  };
  return (
    <>

      <TwitterModal open={modal === 'about'} onClose={close} title="О проекте" width={560}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          <div style={{ display: 'flex', gap: 14, alignItems: 'center', padding: '16px', background: 'var(--accent-subtle)', borderRadius: 14, border: '1px solid var(--border)' }}>
            <CatLogo size={52} />
            <div>
              <div style={{ fontWeight: 800, fontSize: 20, marginBottom: 4 }}>UniMeow</div>
              <div style={{ fontSize: 13, color: 'var(--text-muted)', lineHeight: 1.5 }}>Социальная сеть для студентов и преподавателей российских университетов</div>
            </div>
          </div>
          {[
            ['mission', <><UniIcon size={18} /> Миссия</>, 'Объединить студентов разных университетов в одном месте — делиться знаниями, опытом и находить единомышленников.'],
            ['verify', <><CheckIcon size={18} /> Верификация</>, 'Подтвердите статус студента или сотрудника через университетский email — и ваши посты будут помечены.'],
            ['tech', <><StarIcon size={18} /> Технологии</>, 'GraphQL API, микросервисная архитектура, Redis-ленты, Kafka-события, MinIO-медиа.'],
            ['security', <><ShieldIcon size={18} /> Безопасность</>, 'Авторизация через Google OAuth2. JWT в httpOnly-куках. Автоматический рефреш токенов.'],
          ].map(([key, title, text]) => (
            <div key={key}>
              <div style={{ fontWeight: 700, fontSize: 14, marginBottom: 4, display: 'flex', alignItems: 'center', gap: 6 }}>{title}</div>
              <div style={{ fontSize: 13, color: 'var(--text-muted)', lineHeight: 1.6 }}>{text}</div>
            </div>
          ))}
          <div style={{ fontSize: 12, color: 'var(--text-muted)', textAlign: 'center', paddingTop: 4 }}>
            UniMeow — открытый проект.
          </div>
        </div>
        </TwitterModal>


      <TwitterModal open={modal === 'add-uni'} onClose={close} title="Добавить свой ВУЗ" width={520}>
        {submitted ? (
          <div style={{ textAlign: 'center', padding: '32px 16px' }}>
            <div style={{ display: 'flex', justifyContent: 'center', marginBottom: 12 }}><CheckIcon size={40} color="var(--accent)" /></div>
            <div style={{ fontWeight: 700, fontSize: 17, marginBottom: 8 }}>Заявка отправлена!</div>
            <div style={{ fontSize: 14, color: 'var(--text-muted)', marginBottom: 20 }}>Заявка будет рассмотрена в ближайшее время.</div>
            <Button onClick={close} variant="secondary">Закрыть</Button>
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
            <p style={{ margin: 0, fontSize: 14, color: 'var(--text-muted)', lineHeight: 1.6 }}>
              Укажите название, короткое название, домены для верификации и иконку. После одобрения администратор сможет отдельно добавить факультеты.
            </p>
            <Input label="Название университета *" value={uniForm.name} onChange={e => setUniForm(f => ({ ...f, name: e.target.value }))} placeholder="МГУ им. Ломоносова" />
            <Input label="Короткое название *" value={uniForm.shortName} onChange={e => setUniForm(f => ({ ...f, shortName: e.target.value }))} placeholder="МГУ" />
            <Input label="Поддомен *" value={uniForm.subdomain} onChange={e => setUniForm(f => ({ ...f, subdomain: e.target.value.toLowerCase().replace(/[^a-z0-9-]/g, '') }))} placeholder="mgu" hint="Латинские буквы, цифры и дефис — например «hse» или «spbgu»" />
            <Input label="Студенческий email-домен *" value={uniForm.studentDomain} onChange={e => setUniForm(f => ({ ...f, studentDomain: e.target.value }))} placeholder="student.university.ru" />
            <Input label="Сотруднический email-домен *" value={uniForm.employeeDomain} onChange={e => setUniForm(f => ({ ...f, employeeDomain: e.target.value }))} placeholder="staff.university.ru" />
            <ImageUploadField label="Иконка вуза (SVG или PNG)" value={uniForm.iconUrl} onChange={v => setUniForm(f => ({ ...f, iconUrl: v }))} accept=".svg,.png,image/svg+xml,image/png" bucket="university-icons" />
            {error && <div style={{ color: 'var(--like)', fontSize: 13 }}>{error}</div>}
            <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
              <Button variant="secondary" onClick={close}>Отмена</Button>
              <Button onClick={submitUniversity} loading={loading} disabled={!uniForm.name || !uniForm.shortName || !uniForm.subdomain || !uniForm.studentDomain || !uniForm.employeeDomain}>Отправить заявку</Button>
            </div>
          </div>
        )}
      </TwitterModal>


      <TwitterModal open={modal === 'add-faculty'} onClose={close} title="Добавить свой факультет" width={520}>
        {submitted ? (
          <div style={{ textAlign: 'center', padding: '32px 16px' }}>
            <div style={{ display: 'flex', justifyContent: 'center', marginBottom: 12 }}><CheckIcon size={40} color="var(--accent)" /></div>
            <div style={{ fontWeight: 700, fontSize: 17, marginBottom: 8 }}>Заявка отправлена!</div>
            <div style={{ fontSize: 14, color: 'var(--text-muted)', marginBottom: 20 }}>Администратор проверит факультет и добавит его к университету.</div>
            <Button onClick={close} variant="secondary">Закрыть</Button>
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
            <p style={{ margin: 0, fontSize: 14, color: 'var(--text-muted)', lineHeight: 1.6 }}>
              Если вашего факультета нет в списке, выберите университет и отправьте заявку. После одобрения администратором факультет и подтопики создадутся автоматически.
            </p>
            <select value={facultyForm.universityId} onChange={e => setFacultyForm(f => ({ ...f, universityId: e.target.value }))} style={{
              padding: '10px 12px', borderRadius: 10, border: '1px solid var(--border)',
              background: 'var(--surface)', color: 'var(--text)', fontFamily: 'inherit',
            }}>
              <option value="">Выберите университет *</option>
              {universities.map(u => <option key={u.id} value={u.id}>{u.name} ({u.shortName})</option>)}
            </select>
            <Input label="Название факультета *" value={facultyForm.facultyName} onChange={e => setFacultyForm(f => ({ ...f, facultyName: e.target.value }))} placeholder="Факультет компьютерных наук" />
            <Input label="Короткое название *" value={facultyForm.shortName} onChange={e => setFacultyForm(f => ({ ...f, shortName: e.target.value }))} placeholder="ФКН" />
            {error && <div style={{ color: 'var(--like)', fontSize: 13 }}>{error}</div>}
            <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
              <Button variant="secondary" onClick={close}>Отмена</Button>
              <Button onClick={submitFaculty} loading={loading} disabled={!facultyForm.universityId || !facultyForm.facultyName.trim() || !facultyForm.shortName.trim()}>Отправить заявку</Button>
            </div>
          </div>
        )}
      </TwitterModal>


      <TwitterModal open={modal === 'add-program'} onClose={close} title="Добавить свою программу" width={520}>
        {submitted ? (
          <div style={{ textAlign: 'center', padding: '32px 16px' }}>
            <div style={{ display: 'flex', justifyContent: 'center', marginBottom: 12 }}><CheckIcon size={40} color="var(--accent)" /></div>
            <div style={{ fontWeight: 700, fontSize: 17, marginBottom: 8 }}>Заявка отправлена</div>
            <div style={{ fontSize: 14, color: 'var(--text-muted)', marginBottom: 20 }}>Администратор проверит программу и после одобрения добавит её к факультету.</div>
            <Button onClick={close} variant="secondary">Закрыть</Button>
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
            <p style={{ margin: 0, fontSize: 14, color: 'var(--text-muted)', lineHeight: 1.6 }}>
              Программа создаётся внутри выбранного факультета после одобрения администратором. Посты автора будут попадать в ленту университета, факультета и программы автоматически.
            </p>
            <select value={programForm.facultyId} onChange={e => setProgramForm(f => ({ ...f, facultyId: e.target.value }))} style={{
              padding: '10px 12px', borderRadius: 10, border: '1px solid var(--border)',
              background: 'var(--surface)', color: 'var(--text)', fontFamily: 'inherit',
            }}>
              <option value="">Выберите факультет *</option>
              {faculties.map(f => <option key={f.id} value={f.id}>{f.name} ({f.shortName})</option>)}
            </select>
            <Input label="Название программы *" value={programForm.name} onChange={e => setProgramForm(f => ({ ...f, name: e.target.value }))} placeholder="Программная инженерия" />
            <Input label="Короткое название *" value={programForm.shortName} onChange={e => setProgramForm(f => ({ ...f, shortName: e.target.value }))} placeholder="ПИ" />
            {error && <div style={{ color: 'var(--like)', fontSize: 13 }}>{error}</div>}
            <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
              <Button variant="secondary" onClick={close}>Отмена</Button>
              <Button onClick={submitProgram} loading={loading} disabled={!programForm.facultyId || !programForm.name.trim() || !programForm.shortName.trim()}>Отправить заявку</Button>
            </div>
          </div>
        )}
      </TwitterModal>


      <TwitterModal open={modal === 'suggest'} onClose={close} title="Обратная связь" width={520}>
        {submitted ? (
          <div style={{ textAlign: 'center', padding: '32px 16px' }}>
            <div style={{ display: 'flex', justifyContent: 'center', marginBottom: 12 }}><HeartIcon size={40} color="var(--like)" filled /></div>
            <div style={{ fontWeight: 700, fontSize: 17, marginBottom: 8 }}>Спасибо!</div>
            <div style={{ fontSize: 14, color: 'var(--text-muted)', marginBottom: 20 }}>Ваше предложение получено.</div>
            <Button onClick={close} variant="secondary">Закрыть</Button>
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
            <p style={{ margin: 0, fontSize: 14, color: 'var(--text-muted)', lineHeight: 1.6 }}>
              Предложите улучшение, опишите новый функционал или сообщите о баге.
            </p>
            <Input label="Сообщение *" value={suggestion} onChange={e => setSuggestion(e.target.value)} multiline rows={5} placeholder="Опишите улучшение или баг..." />
            {error && <div style={{ color: 'var(--like)', fontSize: 13 }}>{error}</div>}
            <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
              <Button variant="secondary" onClick={close}>Отмена</Button>
              <Button onClick={submitSuggestion} loading={loading} disabled={!suggestion.trim()}>Отправить</Button>
            </div>
          </div>
        )}
      </TwitterModal>
    </>
  );
}

Object.assign(window, {
  AppLayout, Sidebar, BottomNav, RightRail, UniBadge, ThemeToggle, CatLogo, AppModals, TwitterModal,
  HomeIcon, SearchIcon, UserIcon, GearIcon, HeartIcon, CommentIcon, ShareIcon, FeatherIcon,
  PlusIcon, TrashIcon, EditIcon, CheckIcon, ShieldIcon, DotsIcon, InfoIcon, UniIcon, StarIcon,
  ChevronDownIcon, BackArrowIcon, TeacherIcon, BellIcon,
});
