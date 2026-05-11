


function LoginPage({ onNavigate }) {
  const [banNotice, setBanNotice] = React.useState(() => {
    const params = new URLSearchParams(window.location.search);
    if (params.get('banned') !== 'permanent') return null;
    return { reason: params.get('reason') || '' };
  });

  const closeBanNotice = () => {
    setBanNotice(null);
    if (window.location.pathname === '/login' && window.location.search.includes('banned=')) {
      window.history.replaceState({}, '', '/login');
    }
  };

  return (
    <>
      <div style={{
        minHeight: '100vh',
        background: 'radial-gradient(circle at 20% 20%, var(--accent-subtle) 0%, transparent 38%), radial-gradient(circle at 80% 0%, var(--surface-2) 0%, transparent 42%), var(--bg)',
        display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 24,
      }}>
        <div style={{ width: '100%', maxWidth: 520 }}>
          <div style={{ textAlign: 'center', marginBottom: 34 }}>
            <CatLogo size={92} />
            <h1 style={{ margin: '10px 0 0', fontSize: 42, fontWeight: 900, letterSpacing: '-1px' }}>UniMeow</h1>
          </div>

          {window.MOCK?.enabled && (
            <div style={{ background: 'var(--accent-subtle)', border: '1px solid var(--accent)', borderRadius: 12, padding: '12px 16px', marginBottom: 16, display: 'flex', gap: 10, alignItems: 'center' }}>
              <StarIcon size={18} />
              <div>
                <div style={{ fontWeight: 700, fontSize: 13, color: 'var(--accent)' }}>Демо-режим активен</div>
                <div style={{ fontSize: 12, color: 'var(--text-muted)', lineHeight: 1.4 }}>Все данные тестовые. Нажмите «Демо-вход» и выберите персонажа.</div>
              </div>
            </div>
          )}

          <div style={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 22, padding: 34 }}>
            <h2 style={{ margin: '0 0 10px', fontSize: 29, fontWeight: 800 }}>Присоединяйтесь к обсуждению</h2>
            <p style={{ margin: '0 0 28px', color: 'var(--text-muted)', fontSize: 16, lineHeight: 1.65 }}>
              Войдите через Google, чтобы читать подписки, публиковать записи и подтверждать университетский статус.
            </p>

            <button onClick={() => API.loginWithGoogle()} style={{
              width: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 12,
              padding: '16px 22px', borderRadius: 14, border: '1.5px solid var(--border)',
              background: 'var(--surface)', color: 'var(--text)', fontFamily: 'inherit',
              fontSize: 16, fontWeight: 700, cursor: 'pointer', marginBottom: 12,
            }}>
              <GoogleIcon /> Войти через Google
            </button>

            {window.MOCK?.enabled && (
              <button onClick={() => window.dispatchEvent(new CustomEvent('mock-show-login'))} style={{
                width: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 12,
                padding: '16px 22px', borderRadius: 14, border: 'none',
                background: 'var(--accent)', color: '#fff', fontFamily: 'inherit',
                fontSize: 16, fontWeight: 800, cursor: 'pointer',
              }}>
                Войти как демо-пользователь
              </button>
            )}

            <Divider />
            <p style={{ textAlign: 'center', fontSize: 13, color: 'var(--text-muted)', margin: 0 }}>
              Или{' '}
              <span onClick={() => onNavigate('/')} style={{ color: 'var(--accent)', cursor: 'pointer', fontWeight: 600 }}>продолжить без входа</span>
            </p>
          </div>

          <div style={{
            marginTop: 16,
            display: 'flex',
            justifyContent: 'center',
            gap: 10,
            flexWrap: 'wrap',
            fontSize: 12.5,
            color: 'var(--text-muted)',
          }}>
          </div>
        </div>
      </div>
      <Modal open={!!banNotice} onClose={closeBanNotice} title="Аккаунт заблокирован" width={460}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <div style={{ color: 'var(--text-muted)', fontSize: 14, lineHeight: 1.55 }}>
            Этот Google аккаунт заблокирован бессрочно. Войти или создать новый аккаунт с ним нельзя.
          </div>
          <div style={{ padding: '12px 14px', border: '1px solid var(--border)', borderRadius: 10, background: 'var(--surface-2)' }}>
            <div style={{ fontSize: 12, color: 'var(--text-muted)', fontWeight: 700, textTransform: 'uppercase', marginBottom: 4 }}>
              Причина
            </div>
            <div style={{ fontSize: 14, lineHeight: 1.5 }}>
              {banNotice?.reason || 'Причина не указана'}
            </div>
          </div>
          <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
            <Button variant="secondary" onClick={closeBanNotice}>Понятно</Button>
          </div>
        </div>
      </Modal>
    </>
  );
}

function CompleteRegistrationPage({ onNavigate, onUserUpdated }) {
  const USERNAME_MAX_LENGTH = 30;
  const [username, setUsername] = React.useState('');
  const [name, setName] = React.useState('');
  const [surname, setSurname] = React.useState('');
  const [error, setError] = React.useState('');
  const [loading, setLoading] = React.useState(false);

  const normalizeUsernameInput = (value) => value.toLowerCase().replace(/[^a-z0-9_]/g, '').slice(0, USERNAME_MAX_LENGTH);
  const validateUsername = (u) => /^[a-z0-9_]{3,30}$/.test(u);

  const submit = async () => {
    if (!validateUsername(username)) {
      setError('Юзернейм: 3-30 символов, только a-z, 0-9, _');
      return;
    }
    setLoading(true); setError('');
    try {
      const data = await API.gql(API.M.updateProfile, {
        input: { username, name: name || undefined, surname: surname || undefined }
      });
      onUserUpdated && onUserUpdated(data.updateProfile);
      onNavigate('/');
    } catch (e) {
      setError(e.message);
    } finally { setLoading(false); }
  };

  return (
    <div style={{
      minHeight: '100vh',
      background: 'radial-gradient(circle at 80% 20%, var(--accent-subtle) 0%, transparent 40%), radial-gradient(circle at 15% 85%, var(--surface-2) 0%, transparent 45%), var(--bg)',
      display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 24,
    }}>
      <div style={{ width: '100%', maxWidth: 440 }}>
        <div style={{ textAlign: 'center', marginBottom: 32 }}>
          <CatLogo size={60} />
          <h2 style={{ margin: 0, fontSize: 28, fontWeight: 900 }}>Почти готово</h2>
          <p style={{ margin: '8px 0 0', color: 'var(--text-muted)', fontSize: 14 }}>
            Осталось выбрать никнейм
          </p>
        </div>

        <div style={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 20, padding: 24, display: 'flex', flexDirection: 'column', gap: 16 }}>
          <Input
            label="Никнейм *"
            value={username}
            onChange={e => setUsername(normalizeUsernameInput(e.target.value))}
            placeholder="your_username"
            hint="3-30 символов: a-z, 0-9, _"
            maxLength={USERNAME_MAX_LENGTH}
            autoFocus
          />
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            <Input label="Имя" value={name} onChange={e => setName(e.target.value)} placeholder="Имя" />
            <Input label="Фамилия" value={surname} onChange={e => setSurname(e.target.value)} placeholder="Фамилия" />
          </div>

          {error && <div style={{ padding: '10px 14px', background: 'oklch(0.97 0.05 15)', border: '1px solid oklch(0.85 0.1 15)', borderRadius: 8, color: 'oklch(0.45 0.22 15)', fontSize: 13 }}>{error}</div>}

          <Button onClick={submit} loading={loading} disabled={!username} full size="lg">
            Начать
          </Button>
        </div>
      </div>
    </div>
  );
}

function GoogleIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24">
      <path d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z" fill="#4285F4"/>
      <path d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" fill="#34A853"/>
      <path d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z" fill="#FBBC05"/>
      <path d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z" fill="#EA4335"/>
    </svg>
  );
}

Object.assign(window, { LoginPage, CompleteRegistrationPage });
