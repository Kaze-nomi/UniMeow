



function SettingsPage({ currentUser, onNavigate, onAccountDeleted }) {
  const [theme, setTheme] = React.useState(document.documentElement.dataset.theme || 'light');
  const [deleteLoading, setDeleteLoading] = React.useState(false);
  const [deleteError, setDeleteError] = React.useState('');
  const [deleteConfirmOpen, setDeleteConfirmOpen] = React.useState(false);
  const [isMobile, setIsMobile] = React.useState(window.innerWidth < 420);
  React.useEffect(() => {
    const h = () => setIsMobile(window.innerWidth < 420);
    window.addEventListener('resize', h);
    return () => window.removeEventListener('resize', h);
  }, []);
  const openModal = (type) => window.dispatchEvent(new CustomEvent('open-modal', { detail: type }));
  const apply = (t) => {
    setTheme(t);
    document.documentElement.dataset.theme = t;
    localStorage.setItem('um-theme', t);
  };

  const ThemeOption = ({ value, label, preview }) => {
    const active = theme === value;
    return (
      <button onClick={() => apply(value)} style={{
        flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'stretch',
        gap: 10, padding: 14, borderRadius: 14,
        border: '2px solid ' + (active ? 'var(--accent)' : 'var(--border)'),
        background: 'var(--surface-2)', cursor: 'pointer',
        fontFamily: 'inherit', textAlign: 'left',
      }}>
        <div style={{
          height: 70, borderRadius: 10, overflow: 'hidden',
          background: preview.bg, position: 'relative',
        }}>
          <div style={{ height: 14, background: preview.surface, borderBottom: '1px solid ' + preview.border }} />
          <div style={{ position: 'absolute', top: 22, left: 10, right: 10, height: 8, background: preview.text, opacity: 0.7, borderRadius: 4 }} />
          <div style={{ position: 'absolute', top: 36, left: 10, width: 60, height: 6, background: preview.muted, borderRadius: 3 }} />
        </div>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <span style={{ fontWeight: 700, fontSize: 14, color: 'var(--text)' }}>{label}</span>
          <span style={{
            width: 18, height: 18, borderRadius: '50%',
            border: '2px solid ' + (active ? 'var(--accent)' : 'var(--border-strong)'),
            background: active ? 'var(--accent)' : 'transparent',
            display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
          }}>
            {active && <CheckIcon size={10} color="#fff" />}
          </span>
        </div>
      </button>
    );
  };

  const deleteAccount = async () => {
    setDeleteLoading(true);
    setDeleteError('');
    try {
      await API.gql(API.M.deleteAccount);
      try { await API.logout(); } catch {}
      API.clearUserCache && API.clearUserCache();
      setDeleteConfirmOpen(false);
      if (onAccountDeleted) onAccountDeleted();
      else onNavigate('/login');
    } catch (e) {
      setDeleteError(API.userMessage ? API.userMessage(e.message) : e.message);
    } finally {
      setDeleteLoading(false);
    }
  };

  return (
    <div>
      <div style={{ position: 'sticky', top: 0, zIndex: 10, background: 'var(--header-bg)', backdropFilter: 'saturate(180%) blur(12px)', WebkitBackdropFilter: 'saturate(180%) blur(12px)', borderBottom: '1px solid var(--border)', padding: '12px 16px' }} data-um-header>
        <h2 style={{ margin: 0, fontSize: 20, fontWeight: 800 }}>Настройки</h2>
      </div>

      <div style={{ padding: 16 }}>
        <h3 style={{ margin: '0 0 4px', fontSize: 17, fontWeight: 700 }}>Внешний вид</h3>
        <p style={{ margin: '0 0 14px', color: 'var(--text-muted)', fontSize: 14, lineHeight: 1.5 }}>
          Выберите тему оформления.
        </p>
        <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
          <ThemeOption value="light" label="Светлая" preview={{ bg: '#ffffff', surface: '#f7f9f9', border: '#eff3f4', text: '#0f1419', muted: '#536471' }} />
          <ThemeOption value="dark"  label="Тёмная"     preview={{ bg: '#15202b', surface: '#1e2732', border: '#38444d', text: '#f7f9f9', muted: '#8b98a5' }} />
        </div>
      </div>

      {isMobile && (
        <div style={{ padding: 16, marginTop: 8 }}>
          <h3 style={{ margin: '0 0 4px', fontSize: 17, fontWeight: 700 }}>Сообщество</h3>
          <p style={{ margin: '0 0 14px', color: 'var(--text-muted)', fontSize: 14, lineHeight: 1.5 }}>
            Помогите проекту стать лучше: отправьте обратную связь или добавьте свой ВУЗ, факультет или программу.
          </p>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 0, border: '1px solid var(--border)', borderRadius: 14, overflow: 'hidden', background: 'var(--surface-2)' }}>
            {[
              { type: 'suggest', label: 'Обратная связь' },
              { type: 'add-uni', label: 'Добавить свой ВУЗ' },
              { type: 'add-faculty', label: 'Добавить свой факультет' },
              { type: 'add-program', label: 'Добавить свою программу' },
            ].map((item, i, arr) => (
              <button key={item.type} onClick={() => openModal(item.type)} style={{
                display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                width: '100%', padding: '14px 16px',
                border: 'none',
                borderBottom: i < arr.length - 1 ? '1px solid var(--border)' : 'none',
                background: 'transparent', cursor: 'pointer',
                textAlign: 'left', fontFamily: 'inherit', fontSize: 15,
                color: 'var(--text)',
              }}>
                <span style={{ fontWeight: 500 }}>{item.label}</span>
                <span style={{ color: 'var(--text-muted)', fontSize: 18, lineHeight: 1 }}>›</span>
              </button>
            ))}
          </div>
          <button onClick={() => onNavigate('/about')} style={{
            display: 'flex', alignItems: 'center', justifyContent: 'space-between',
            width: '100%', padding: '14px 16px', marginTop: 12,
            border: '1px solid var(--border)', borderRadius: 14,
            background: 'var(--surface-2)', cursor: 'pointer',
            textAlign: 'left', fontFamily: 'inherit', fontSize: 15,
            color: 'var(--text)',
          }}>
            <span style={{ fontWeight: 500 }}>О проекте</span>
            <span style={{ color: 'var(--text-muted)', fontSize: 18, lineHeight: 1 }}>›</span>
          </button>
        </div>
      )}

      {currentUser && !currentUser.isBanned && (
        <div style={{ padding: 16, marginTop: 8 }}>
          <h3 style={{ margin: '0 0 4px', fontSize: 17, fontWeight: 700 }}>Аккаунт</h3>
          <p style={{ margin: '0 0 14px', color: 'var(--text-muted)', fontSize: 14, lineHeight: 1.5 }}>
            Удаление аккаунта необратимо. Посты, комментарии, подписки, сессии и данные профиля будут удалены.
          </p>
          {deleteError && (
            <div style={{ marginBottom: 12, padding: '10px 14px', background: 'var(--like-subtle)', color: 'var(--like)', borderRadius: 10, fontSize: 13 }}>
              {deleteError}
            </div>
          )}
          <Button variant="secondary" onClick={() => { setDeleteError(''); setDeleteConfirmOpen(true); }} loading={deleteLoading} style={{ color: 'var(--danger)', borderColor: 'rgba(244, 63, 94, 0.35)' }}>
            Удалить аккаунт
          </Button>
        </div>
      )}
      <Modal open={deleteConfirmOpen} onClose={() => !deleteLoading && setDeleteConfirmOpen(false)} title="Удалить аккаунт?" width={460}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <div style={{ color: 'var(--text-muted)', fontSize: 14, lineHeight: 1.5 }}>
            Аккаунт, профиль, посты, комментарии, подписки и активные сессии будут удалены без возможности восстановления.
          </div>
          {deleteError && (
            <div style={{ padding: '10px 14px', background: 'var(--like-subtle)', color: 'var(--like)', borderRadius: 10, fontSize: 13 }}>
              {deleteError}
            </div>
          )}
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
            <Button variant="secondary" onClick={() => setDeleteConfirmOpen(false)} disabled={deleteLoading}>Отмена</Button>
            <Button variant="danger" onClick={deleteAccount} loading={deleteLoading}>Удалить</Button>
          </div>
        </div>
      </Modal>
    </div>
  );
}


function EditProfileModal({ open, onClose, currentUser, onUserUpdated }) {
  const STATUS_MAX_LENGTH = 80;
  const USERNAME_MAX_LENGTH = 30;
  const [form, setForm] = React.useState({});
  const [loading, setLoading] = React.useState(false);
  const [error, setError] = React.useState('');
  const [faculties, setFaculties] = React.useState([]);
  const [programs, setPrograms] = React.useState([]);

  React.useEffect(() => {
    if (!open || !currentUser) return;
    setForm({
      username: currentUser.username || '',
      name: currentUser.name || '',
      surname: currentUser.surname || '',
      bio: currentUser.bio || '',
      status: currentUser.status || '',
      avatarUrl: currentUser.avatarUrl || '',
      coverUrl: currentUser.coverUrl || '',
      facultyId: currentUser.faculty?.id || '',
      programId: currentUser.program?.id || '',
      course: currentUser.course || '',
      graduationYear: currentUser.graduationYear || '',
      educationLevel: currentUser.educationLevel || '',
    });
    setError('');
    if (currentUser.university?.id) {
      API.gql(API.Q.listFaculties, { universityId: currentUser.university.id })
        .then(d => setFaculties(d.listFaculties || []))
        .catch(() => setFaculties([]));
    }
    if (currentUser.faculty?.id) {
      API.gql(API.Q.listPrograms, { facultyId: currentUser.faculty.id })
        .then(d => setPrograms(d.listPrograms || []))
        .catch(() => setPrograms([]));
    }
  }, [open, currentUser]);


  React.useEffect(() => {
    if (!form.facultyId) { setPrograms([]); return; }
    API.gql(API.Q.listPrograms, { facultyId: form.facultyId })
      .then(d => setPrograms(d.listPrograms || []))
      .catch(() => setPrograms([]));
  }, [form.facultyId]);

  if (!open) return null;
  const set = (k) => (e) => setForm(f => ({ ...f, [k]: e.target.value }));
  const setLimited = (k, maxLength) => (e) => setForm(f => ({ ...f, [k]: e.target.value.slice(0, maxLength) }));
  const setUsername = (e) => setForm(f => ({
    ...f,
    username: e.target.value.toLowerCase().replace(/[^a-z0-9_]/g, '').slice(0, USERNAME_MAX_LENGTH),
  }));

  const save = async () => {
    setLoading(true); setError('');
    try {
      if (!/^[a-z0-9_]{3,30}$/.test(form.username || '')) {
        setError('Юзернейм: 3-30 символов, только a-z, 0-9, _');
        return;
      }
      const input = {};
      ['username','name','surname','bio','status','avatarUrl','coverUrl'].forEach(k => {
        if (form[k] !== undefined) input[k] = form[k];
      });
      if (form.educationLevel !== undefined && form.educationLevel !== '') {
        input.educationLevel = form.educationLevel;
      }
      if (form.facultyId !== undefined) input.facultyId = form.facultyId;
      if (form.programId !== undefined) input.programId = form.programId;
      if (form.course !== undefined && form.course !== '') input.course = parseInt(form.course);
      const d = await API.gql(API.M.updateProfile, { input });
      onUserUpdated && onUserUpdated(d.updateProfile);
      onClose();
    } catch (e) { setError(e.message); }
    finally { setLoading(false); }
  };

  return (
    <div onClick={onClose} style={{ position: 'fixed', inset: 0, background: 'rgba(91,112,131,0.4)', backdropFilter: 'blur(4px)', zIndex: 1000, display: 'flex', alignItems: 'flex-start', justifyContent: 'center', padding: '40px 16px', overflowY: 'auto' }}>
      <div onClick={e => e.stopPropagation()} style={{ background: 'var(--bg)', borderRadius: 16, width: '100%', maxWidth: 600, padding: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '8px 16px', borderBottom: '1px solid var(--border)' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
            <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', padding: 6, color: 'var(--text)', fontSize: 22, lineHeight: 1 }}>×</button>
            <h3 style={{ margin: 0, fontSize: 18, fontWeight: 800 }}>Редактировать профиль</h3>
          </div>
          <Button onClick={save} loading={loading} size="sm">Сохранить</Button>
        </div>
        <div style={{ padding: 16, display: 'flex', flexDirection: 'column', gap: 14 }}>
          {error && <div style={{ padding: '10px 14px', background: 'var(--like-subtle)', color: 'var(--like)', borderRadius: 10, fontSize: 13 }}>{error}</div>}
          <Input label="Никнейм" value={form.username || ''} onChange={setUsername} maxLength={USERNAME_MAX_LENGTH} hint="3-30 символов: a-z, 0-9, _" />
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            <Input label="Имя" value={form.name || ''} onChange={set('name')} />
            <Input label="Фамилия" value={form.surname || ''} onChange={set('surname')} />
          </div>
          <Input label="О себе" value={form.bio || ''} onChange={set('bio')} multiline rows={3} />
          <Input label="Статус" value={form.status || ''} onChange={setLimited('status', STATUS_MAX_LENGTH)} maxLength={STATUS_MAX_LENGTH} hint={`${(form.status || '').length}/${STATUS_MAX_LENGTH}`} />
          <ImageUploadField label="Аватар" value={form.avatarUrl || ''} onChange={v => setForm(f => ({ ...f, avatarUrl: v }))} preview="circle" bucket="user-avatars" />
          <ImageUploadField label="Обложка (баннер)" value={form.coverUrl || ''} onChange={v => setForm(f => ({ ...f, coverUrl: v }))} preview="wide" bucket="user-banners" />

          {currentUser.university && (
            <>
              <div style={{ height: 1, background: 'var(--border)', margin: '4px 0' }} />
              <div style={{ fontWeight: 700, fontSize: 14, color: 'var(--text)' }}>Учёба · {currentUser.university.name}</div>
              {faculties.length > 0 && (
                <SelectField label="Факультет" value={form.facultyId || ''} onChange={e => setForm(f => ({ ...f, facultyId: e.target.value, programId: '' }))}
                  options={[{ value: '', label: 'Не указан' }, ...faculties.map(f => ({ value: f.id, label: f.name }))]} />
              )}
              {form.facultyId && programs.length > 0 && (
                <SelectField label="Программа" value={form.programId || ''} onChange={set('programId')}
                  options={[{ value: '', label: 'Не указана' }, ...programs.map(p => ({ value: p.id, label: p.name }))]} />
              )}
              <Input type="number" label="Курс (1–8)" value={form.course || ''} onChange={e => setForm(f => ({ ...f, course: Math.min(8, Math.max(1, parseInt(e.target.value) || 0)) || '' }))} min={1} max={8} />
              <SelectField label="Уровень образования" value={form.educationLevel || ''} onChange={set('educationLevel')}
                options={[
                  { value: '', label: 'Не указано' },
                  { value: 'BACHELOR', label: 'Бакалавр' },
                  { value: 'MASTER', label: 'Магистр' },
                  { value: 'PHD', label: 'Аспирант' },
                  { value: 'SPECIALIST', label: 'Специалист' },
                ]} />
            </>
          )}
        </div>
      </div>
    </div>
  );
}

function SelectField({ label, value, onChange, options }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
      {label && <label style={{ fontSize: 13, fontWeight: 600, color: 'var(--text-muted)' }}>{label}</label>}
      <select value={value} onChange={onChange} style={{
        padding: '10px 14px', borderRadius: 10, border: '1.5px solid var(--border)',
        background: 'var(--surface)', color: 'var(--text)', fontFamily: 'inherit', fontSize: 14, outline: 'none',
      }}>
        {options.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>
    </div>
  );
}


function VerifyModal({ open, onClose, currentUser, onUserUpdated }) {
  const [stage, setStage] = React.useState('email');
  const [email, setEmail] = React.useState('');
  const [code, setCode] = React.useState('');
  const [codeSent, setCodeSent] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [msg, setMsg] = React.useState('');
  const [err, setErr] = React.useState('');


  const [faculties, setFaculties] = React.useState([]);
  const [programs, setPrograms] = React.useState([]);
  const [facultyId, setFacultyId] = React.useState('');
  const [programId, setProgramId] = React.useState('');
  const [loadingFaculties, setLoadingFaculties] = React.useState(false);
  const [loadingPrograms, setLoadingPrograms] = React.useState(false);

  const resetState = React.useCallback(() => {
    setEmail(currentUser?.emailUniversity || '');
    setCode(''); setCodeSent(false); setMsg(''); setErr('');
    setStage('email');
    setFaculties([]); setPrograms([]); setFacultyId(''); setProgramId('');
  }, [currentUser]);

  React.useEffect(() => {
    if (open) resetState();
  }, [open]);


  React.useEffect(() => {
    if (stage !== 'profile') return;
    const uniId = currentUser?.university?.id;
    if (!uniId) {
      setFaculties([]);
      setFacultyId('');
      return;
    }
    setLoadingFaculties(true);
    API.gql(API.Q.listFaculties, { universityId: uniId })
      .then(d => { setFaculties(d.listFaculties || []); setFacultyId(currentUser?.faculty?.id || ''); })
      .catch(() => setFaculties([]))
      .finally(() => setLoadingFaculties(false));
  }, [stage, currentUser?.university?.id, currentUser?.faculty?.id]);


  React.useEffect(() => {
    if (!facultyId) { setPrograms([]); setProgramId(''); return; }
    setLoadingPrograms(true);
    API.gql(API.Q.listPrograms, { facultyId })
      .then(d => { setPrograms(d.listPrograms || []); setProgramId(currentUser?.program?.id || ''); })
      .catch(() => setPrograms([]))
      .finally(() => setLoadingPrograms(false));
  }, [facultyId]);

  if (!open) return null;

  const send = async () => {
    setBusy(true); setErr(''); setMsg('');
    try {
      const d = await API.gql(API.M.sendVerificationCode, { email });
      if (d.sendVerificationCode.success) { setCodeSent(true); setMsg('Код отправлен на ' + email); }
      else setErr(d.sendVerificationCode.message || 'Ошибка');
    } catch (e) { setErr(formatVerificationError(e)); } finally { setBusy(false); }
  };

  const verify = async () => {
    setBusy(true); setErr(''); setMsg('');
    try {
      const d = await API.gql(API.M.verifyEmailCode, { code });
      if (d.verifyEmailCode.success) {
        const ud = await API.gql(API.Q.me);
        const verifiedUser = ud.me;
        onUserUpdated && onUserUpdated(verifiedUser);
        if (!verifiedUser?.university?.id) {
          setErr('Не удалось определить ВУЗ по подтверждённому домену.');
          return;
        }
        setMsg('');
        setStage('profile');
      } else setErr(formatVerificationCodeError(d.verifyEmailCode.error));
    } catch (e) { setErr(formatVerificationError(e)); } finally { setBusy(false); }
  };

  const saveProfile = async () => {
    if (!facultyId || !programId) { setErr('Выберите факультет и программу'); return; }
    setBusy(true); setErr('');
    try {
      const d = await API.gql(API.M.updateProfile, { input: { facultyId, programId } });
      onUserUpdated && onUserUpdated(d.updateProfile);
      onClose();
    } catch (e) { setErr(e.message); } finally { setBusy(false); }
  };

  const titleMap = { email: 'Верификация университета', profile: 'Ваш факультет и программа' };

  return (
    <div onClick={onClose} style={{ position: 'fixed', inset: 0, background: 'rgba(91,112,131,0.4)', backdropFilter: 'blur(4px)', zIndex: 1000, display: 'flex', alignItems: 'flex-start', justifyContent: 'center', padding: '40px 16px' }}>
      <div onClick={e => e.stopPropagation()} style={{ background: 'var(--bg)', borderRadius: 16, width: '100%', maxWidth: 480, padding: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 16, padding: '8px 16px', borderBottom: '1px solid var(--border)' }}>
          <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', padding: 6, color: 'var(--text)', fontSize: 22, lineHeight: 1 }}>×</button>
          <h3 style={{ margin: 0, fontSize: 18, fontWeight: 800 }}>{titleMap[stage]}</h3>
        </div>

        {stage === 'email' && (
          <div style={{ padding: 20, display: 'flex', flexDirection: 'column', gap: 14 }}>
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
              <Badge color={currentUser.isStudentVerified ? 'green' : 'muted'}>
                {currentUser.isStudentVerified ? <CheckIcon size={10} /> : <span style={{ width: 10, height: 10, borderRadius: '50%', border: '1.5px solid currentColor', display: 'inline-block' }} />} Студент
              </Badge>
              <Badge color={currentUser.isEmployeeVerified ? 'green' : 'muted'}>
                {currentUser.isEmployeeVerified ? <CheckIcon size={10} /> : <span style={{ width: 10, height: 10, borderRadius: '50%', border: '1.5px solid currentColor', display: 'inline-block' }} />} Сотрудник
              </Badge>
            </div>
            <p style={{ margin: 0, fontSize: 14, lineHeight: 1.6, color: 'var(--text-muted)' }}>
              Введите email вашего университета — пришлём код подтверждения.
            </p>
            <Input label="Email университета" value={email} onChange={e => setEmail(e.target.value)} type="email" placeholder="student@university.edu" />
            <Button onClick={send} loading={busy && !codeSent} disabled={!email} variant="outline">
              {codeSent ? 'Отправить снова' : 'Отправить код'}
            </Button>
            {codeSent && (
              <>
                <Input label="Код из письма" value={code} onChange={e => setCode(e.target.value)} placeholder="123456" />
                <Button onClick={verify} loading={busy && codeSent} disabled={!code}>Подтвердить</Button>
              </>
            )}
            {msg && <div style={{ padding: '10px 14px', background: 'var(--accent-subtle)', border: '1px solid var(--border)', borderRadius: 10, color: 'var(--text)', fontSize: 13 }}>{msg}</div>}
            {err && <div style={{ padding: '10px 14px', background: 'var(--like-subtle)', color: 'var(--like)', borderRadius: 10, fontSize: 13 }}>{err}</div>}
          </div>
        )}

        {stage === 'profile' && (
          <div style={{ padding: 20, display: 'flex', flexDirection: 'column', gap: 14 }}>
            <p style={{ margin: 0, fontSize: 14, lineHeight: 1.6, color: 'var(--text-muted)' }}>
              Укажите факультет и образовательную программу, чтобы ваши записи попадали в нужную ленту.
            </p>
            {currentUser?.university && (
              <div style={{ padding: '10px 14px', background: 'var(--accent-subtle)', border: '1px solid var(--border)', borderRadius: 10, color: 'var(--text)', fontSize: 13 }}>
                ВУЗ определён автоматически: {currentUser.university.name}
              </div>
            )}
            {loadingFaculties ? (
              <div style={{ display: 'flex', justifyContent: 'center', padding: 16 }}><Spinner size={24} /></div>
            ) : (
              <SelectField label="Факультет" value={facultyId} onChange={e => setFacultyId(e.target.value)}
                options={[{ value: '', label: 'Выберите факультет' }, ...faculties.map(f => ({ value: f.id, label: f.name }))]} />
            )}
            {facultyId && (
              loadingPrograms ? (
                <div style={{ display: 'flex', justifyContent: 'center', padding: 8 }}><Spinner size={20} /></div>
              ) : (
                <SelectField label="Образовательная программа" value={programId} onChange={e => setProgramId(e.target.value)}
                  options={[{ value: '', label: 'Выберите программу' }, ...programs.map(p => ({ value: p.id, label: p.name }))]} />
              )
            )}
            {err && <div style={{ padding: '10px 14px', background: 'var(--like-subtle)', color: 'var(--like)', borderRadius: 10, fontSize: 13 }}>{err}</div>}
            <div style={{ display: 'flex', gap: 8, marginTop: 4 }}>
              <Button onClick={saveProfile} loading={busy} disabled={!facultyId || !programId}>Завершить</Button>
              <Button variant="secondary" onClick={onClose}>Пропустить</Button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

function formatVerificationError(e) {
  const message = e?.message || '';
  if (/mail service|почтовый сервис|smtp|mail delivery|unavailable/i.test(message)) {
    return 'Почтовый сервис временно недоступен. Попробуйте позже.';
  }
  if (/unknown.?domain|not a registered university domain|domain .*registered/i.test(message)) {
    return 'Домен почты не зарегистрирован как университетский. Проверьте адрес или отправьте заявку на добавление ВУЗа.';
  }
  if (/already linked|already used/i.test(message)) {
    return 'Этот университетский email уже привязан к другому аккаунту.';
  }
  return message || 'Не удалось выполнить запрос';
}

function formatVerificationCodeError(error) {
  const code = String(error || '').trim();
  if (!code) return 'Неверный код';
  if (code === 'INVALID') return 'Неверный код подтверждения. Проверьте код и попробуйте ещё раз.';
  if (code === 'EXPIRED') return 'Код подтверждения истёк. Отправьте новый код.';
  if (code === 'ATTEMPTS_EXCEEDED') return 'Превышено число попыток. Отправьте новый код и попробуйте позже.';
  return formatVerificationError({ message: code });
}

Object.assign(window, { EditProfileModal, VerifyModal, SelectField });

Object.assign(window, { SettingsPage });
