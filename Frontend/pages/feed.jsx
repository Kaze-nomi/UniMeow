

function FeedPage({ currentUser, onNavigate, universitySlug, composeOpen, setComposeOpen }) {
  const NO_UNIVERSITY_SLUG = 'outside';
  const [tab, setTab] = React.useState('trending');
  const [posts, setPosts] = React.useState([]);
  const [cursor, setCursor] = React.useState(null);
  const [hasMore, setHasMore] = React.useState(false);
  const [loading, setLoading] = React.useState(false);
  const [loadingMore, setLoadingMore] = React.useState(false);
  const [error, setError] = React.useState('');
  const loadSeqRef = React.useRef(0);

  const [universityId, setUniversityId] = React.useState(null);
  const [facultyId, setFacultyId] = React.useState(null);
  const [programId, setProgramId] = React.useState(null);
  const [faculties, setFaculties] = React.useState([]);
  const [programs, setPrograms] = React.useState([]);


  const [universities, setUniversities] = React.useState([]);
  const [scopeReady, setScopeReady] = React.useState(!universitySlug || universitySlug === NO_UNIVERSITY_SLUG);
  React.useEffect(() => { API.gql(API.Q.listUniversities).then(d => setUniversities(d.listUniversities || [])); }, []);
  React.useEffect(() => {
    setScopeReady(false);
    if (!universitySlug || universitySlug === NO_UNIVERSITY_SLUG) {
      setUniversityId(null); setFacultyId(null); setProgramId(null); setScopeReady(true); return;
    }
    if (!universities.length) return;
    const selected = universities.find(u => API.universitySlug(u) === universitySlug);
    if (!selected) {
      onNavigate('/feed');
      return;
    }
    setUniversityId(selected.id);
    setFacultyId(null);
    setProgramId(null);
    setScopeReady(true);
  }, [universities, universitySlug, onNavigate]);

  React.useEffect(() => {
    setFacultyId(null);
    setProgramId(null);
    setPrograms([]);
    if (!universityId) { setFaculties([]); return; }
    API.gql(API.Q.listFaculties, { universityId })
      .then(d => setFaculties(d.listFaculties || []))
      .catch(() => setFaculties([]));
  }, [universityId]);

  React.useEffect(() => {
    setProgramId(null);
    if (!facultyId) { setPrograms([]); return; }
    API.gql(API.Q.listPrograms, { facultyId })
      .then(d => setPrograms(d.listPrograms || []))
      .catch(() => setPrograms([]));
  }, [facultyId]);


  React.useEffect(() => {
    const h = (e) => {
      const uni = universities.find(u => u.id === e.detail);
      if (uni) onNavigate('/' + API.universitySlug(uni) + '/');
    };
    window.addEventListener('um-select-uni', h);
    return () => window.removeEventListener('um-select-uni', h);
  }, [universities]);

  const isNoUniversityScope = universitySlug === NO_UNIVERSITY_SLUG;
  const isScopedFeed = Boolean(universityId) || isNoUniversityScope;
  const tabs = isScopedFeed
    ? []
    : currentUser
    ? [
        { id: 'trending', label: 'Рекомендации' },
        { id: 'following', label: 'Подписки', requireAuth: true },
      ]
    : [
        { id: 'trending', label: 'Рекомендации' },
      ];


  React.useEffect(() => {
    if (tab === 'global') setTab('trending');
  }, [currentUser, isScopedFeed]);

  const queryFor = (t) => t === 'following' ? API.Q.followingFeed : API.Q.trendingFeed;
  const keyFor = (t) => t === 'following' ? 'followingFeed' : 'trendingFeed';

  const loadFeed = React.useCallback(async (t, cur = null, uniId = null, facId = null, progId = null, noUniversity = false) => {
    const seq = ++loadSeqRef.current;
    if (!cur) setLoading(true);
    else setLoadingMore(true);
    setError('');
    try {
      const vars = { cursor: cur, size: 15 };
      if (uniId) vars.universityId = uniId;
      if (facId) vars.facultyId = facId;
      if (progId) vars.programId = progId;
      if (noUniversity) vars.topicId = -1;
      const data = await API.gql(queryFor(t), vars);
      const result = data[keyFor(t)];
      if (seq !== loadSeqRef.current) return;
      const nextPosts = result.posts;
      if (!cur) setPosts(nextPosts);
      else setPosts(p => [...p, ...nextPosts]);
      setCursor(result.nextCursor);
      setHasMore(result.hasMore);
    } catch (e) {
      if (seq !== loadSeqRef.current) return;
      if (e.isUnauth && t === 'following') setError('Войдите, чтобы видеть ленту подписок');
      else setError(e.message || 'Ошибка загрузки');
    } finally {
      if (seq === loadSeqRef.current) { setLoading(false); setLoadingMore(false); }
    }
  }, []);

  React.useEffect(() => {
    if (!scopeReady) return;
    setPosts([]); setCursor(null); setHasMore(false);
    if (universitySlug && universitySlug !== NO_UNIVERSITY_SLUG && !universityId) {
      setError('Университет не найден');
      return;
    }
    loadFeed(isScopedFeed ? 'trending' : tab, null, universityId, facultyId, programId, isNoUniversityScope);
  }, [tab, universityId, facultyId, programId, isNoUniversityScope, universitySlug, scopeReady, loadFeed]);

  React.useEffect(() => {
    const h = (e) => {
      if (!scopeReady || (universitySlug && universitySlug !== NO_UNIVERSITY_SLUG && !universityId)) return;
      const newPost = e.detail;
      if (newPost && !isScopedFeed) {
        if (newPost.removeTmpId) {
          setPosts(prev => prev.filter(p => p.id !== newPost.removeTmpId));
          return;
        }
        setPosts(prev => {
          if (newPost.id && String(newPost.id).startsWith('tmp-')) return [newPost, ...prev];
          const idx = prev.findIndex(p => p.id && String(p.id).startsWith('tmp-') && p.authorId === newPost.authorId && p.content === newPost.content);
          if (idx !== -1) { const copy = [...prev]; copy[idx] = newPost; return copy; }
          if (prev.some(p => p.id === newPost.id)) return prev;
          return [newPost, ...prev];
        });
      } else {
        loadFeed(isScopedFeed ? 'trending' : tab, null, universityId, facultyId, programId, isNoUniversityScope);
      }
    };
    window.addEventListener('um-post-created', h);
    return () => window.removeEventListener('um-post-created', h);
  }, [tab, universityId, facultyId, programId, isNoUniversityScope, universitySlug, scopeReady, isScopedFeed, loadFeed]);

  const handleTabClick = (t) => {
    if (t.requireAuth && !currentUser) { onNavigate('/login'); return; }
    setTab(t.id);
  };

  const currentUni = universities.find(u => u.id === universityId);
  const currentTitle = isNoUniversityScope ? 'Без ВУЗа' : currentUni ? currentUni.name : 'Главная';
  return (
    <div>

      <div style={{
        position: 'sticky', top: 0, zIndex: 10,
        background: 'var(--header-bg)',
        backdropFilter: 'saturate(180%) blur(12px)',
        WebkitBackdropFilter: 'saturate(180%) blur(12px)',
        borderBottom: '1px solid var(--border)',
      }}
      data-um-header>

        <div style={{ padding: '14px 16px 0', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            {isScopedFeed && (
              <button onClick={() => { setUniversityId(null); setFacultyId(null); setProgramId(null); onNavigate('/feed'); }} aria-label="Назад"
                style={{ background: 'transparent', border: 'none', cursor: 'pointer', padding: 6, borderRadius: '50%', display: 'inline-flex', color: 'var(--text)' }}>
                <BackArrowIcon size={20} color="var(--text)" />
              </button>
            )}
            <UniversityPicker
              universities={universities}
              currentId={universityId}
              onSelect={(id) => {
                if (id === '__no_university__') {
                  setUniversityId(null);
                  setFacultyId(null);
                  setProgramId(null);
                  onNavigate('/' + NO_UNIVERSITY_SLUG + '/');
                  return;
                }
                const uni = universities.find(u => u.id === id);
                setUniversityId(id);
                setFacultyId(null);
                setProgramId(null);
                onNavigate(id && uni ? '/' + API.universitySlug(uni) + '/' : '/feed');
              }}
              currentName={currentTitle}
              noUniversityActive={isNoUniversityScope}
            />
          </div>
        </div>


        {!isScopedFeed && (
          <div style={{ display: 'flex', marginTop: 12 }}>
            {tabs.map(t => {
              const active = tab === t.id;
              return (
                <button key={t.id} className="um-tab" onClick={() => handleTabClick(t)} style={{
                  flex: 1, padding: '14px 0',
                  border: 'none', background: 'transparent', fontFamily: 'inherit',
                  fontSize: 15, fontWeight: active ? 700 : 500,
                  cursor: 'pointer',
                  color: active ? 'var(--text)' : 'var(--text-muted)',
                  position: 'relative',
                }}>
                  <span>{t.label}</span>
                  {active && (
                    <span style={{
                      position: 'absolute', bottom: 0, left: '50%', transform: 'translateX(-50%)',
                      height: 4, width: 56, borderRadius: 2, background: 'var(--accent)',
                    }} />
                  )}
                </button>
              );
            })}
          </div>
        )}
        {universityId && (
          <FacultyProgramBar
            faculties={faculties}
            programs={programs}
            facultyId={facultyId}
            programId={programId}
            onFaculty={setFacultyId}
            onProgram={setProgramId}
          />
        )}
      </div>


      {currentUser && !isScopedFeed && (
        <InlineCompose
            currentUser={currentUser}
            defaultTopicId={null}
            onCreated={(newPost) => {
              if (!newPost) {
                loadFeed(isScopedFeed ? 'trending' : tab, null, universityId, facultyId, programId, isNoUniversityScope);
                return;
              }
              if (newPost.removeTmpId) {
                setPosts(prev => prev.filter(p => p.id !== newPost.removeTmpId));
                return;
              }
              setPosts(prev => {
                if (newPost.id && String(newPost.id).startsWith('tmp-')) {
                  return [newPost, ...prev];
                }
                const idx = prev.findIndex(p => p.id && String(p.id).startsWith('tmp-') && p.authorId === newPost.authorId && p.content === newPost.content);
                if (idx !== -1) { const copy = [...prev]; copy[idx] = newPost; return copy; }
                if (prev.some(p => p.id === newPost.id)) return prev;
                return [newPost, ...prev];
              });
            }}
          />
      )}


      {loading ? (
        <div>{[...Array(5)].map((_, i) => <PostSkeleton key={i} />)}</div>
      ) : error ? (
        <div style={{ padding: '32px 16px', textAlign: 'center' }}>
          <div style={{ color: 'var(--text-muted)', marginBottom: 12, fontSize: 15 }}>{error}</div>
          {error.includes('Войдите')
            ? <Button onClick={() => onNavigate('/login')}>Войти</Button>
            : <Button variant="secondary" onClick={() => loadFeed(isScopedFeed ? 'trending' : tab, null, universityId, facultyId, programId, isNoUniversityScope)}>Повторить</Button>}
        </div>
      ) : posts.length === 0 ? (
        <EmptyState icon={<EmptyBoxIcon />} title="Пока пусто" subtitle={tab === 'following' && !isScopedFeed ? 'Подпишитесь на кого-нибудь' : undefined} />
      ) : (
        <div>
          {posts.map(post => (
            <PostCard key={post.id} post={post} onNavigate={onNavigate} currentUser={currentUser} />
          ))}
          {hasMore && (
            <InfiniteSentinel
              onIntersect={() => {
                if (!loadingMore) {
                  loadFeed(isScopedFeed ? 'trending' : tab, cursor, universityId, facultyId, programId, isNoUniversityScope);
                }
              }}
              loading={loadingMore}
            />
          )}
        </div>
      )}
    </div>
  );
}


function InlineCompose({ currentUser, onCreated, defaultTopicId }) {
  const [text, setText] = React.useState('');
  const [busy, setBusy] = React.useState(false);
  const [mediaFiles, setMediaFiles] = React.useState([]);
  const [error, setError] = React.useState('');
  const inputRef = React.useRef();
  const textareaRef = React.useRef();
  const MAX_MEDIA_SIZE = 10 * 1024 * 1024;
  const MAX_FILES = 10;

  React.useEffect(() => {
    const ta = textareaRef.current;
    if (!ta) return;
    ta.style.height = 'auto';
    ta.style.height = Math.max(52, ta.scrollHeight) + 'px';
  }, [text]);

  const handleMediaSelect = (e) => {
    const files = Array.from(e.target.files || []);
    e.target.value = '';
    const remaining = MAX_FILES - mediaFiles.length;
    const selected = files.slice(0, remaining);
    if (files.length > remaining) { setError('Можно прикрепить не больше 10 файлов'); return; }
    if (selected.some(file => !file.type.startsWith('image/') && file.size > MAX_MEDIA_SIZE)) {
      setError('Файл слишком большой (максимум 10 МБ)');
      return;
    }
    setError('');
    selected.forEach(file => {
      const reader = new FileReader();
      reader.onload = (ev) => setMediaFiles(prev => [...prev, { file, previewUrl: ev.target.result }]);
      reader.readAsDataURL(file);
    });
  };

  const submit = async () => {
    if (busy) return;
    const submittedText = text.trim();
    if (!submittedText) { setError('Добавьте текст записи'); return; }
    if (text.length > 1000) { setError('Сократите запись до 1000 символов'); return; }
    setBusy(true); setError('');
    const submittedMediaFiles = mediaFiles;
    setText('');
    setMediaFiles([]);
    try {
      const tmpId = 'tmp-' + Date.now();
      const clientRequestId = API.newClientRequestId();
      const optimisticMedia = submittedMediaFiles.map(m => m.previewUrl);
      const optimisticPost = {
        id: tmpId,
        author: currentUser,
        authorId: currentUser?.id,
        content: submittedText,
        mediaUrls: optimisticMedia,
        likesCount: 0,
        commentsCount: 0,
        likedByMe: false,
        createdAt: new Date().toISOString(),
        universityId: currentUser?.university?.id || null,
        facultyId: currentUser?.faculty?.id || null,
        programId: currentUser?.program?.id || null,
      };
      onCreated && onCreated(optimisticPost);
      try {
        const mediaUrls = submittedMediaFiles.length
          ? await Promise.all(submittedMediaFiles.map(m => API.uploadFile(m.file, 'post-media')))
          : [];
        const input = { content: submittedText, mediaUrls };
        const d = await API.gql(API.M.createPost, { input, clientRequestId });
        const created = d.createPost;
        const newPost = {
          ...created,
          author: currentUser,
          likesCount: created.likesCount ?? 0,
          commentsCount: created.commentsCount ?? 0,
          likedByMe: created.likedByMe ?? false,
          universityId: created.universityId ?? currentUser?.university?.id ?? null,
          facultyId: created.facultyId ?? currentUser?.faculty?.id ?? null,
          programId: created.programId ?? currentUser?.program?.id ?? null,
        };
        onCreated && onCreated(newPost);
      } catch (innerErr) {
        onCreated && onCreated({ removeTmpId: tmpId });
        setText(submittedText);
        setMediaFiles(submittedMediaFiles);
        throw innerErr;
      }
    } catch (e) { setError(API.userMessage ? API.userMessage(e.message) : (e.message || 'Не удалось опубликовать')); }
    finally { setBusy(false); }
  };
  return (
    <div style={{ display: 'flex', gap: 12, padding: '12px 16px', borderBottom: '1px solid var(--border)' }}>
      <Avatar user={currentUser} size={42} />
      <div style={{ flex: 1 }}>
        <textarea
          ref={textareaRef}
          value={text} onChange={e => setText(e.target.value)}
          placeholder="Что у вас нового?"
          rows={1}
          style={{
            width: '100%', minHeight: 52, padding: '8px 12px', border: '1.5px solid var(--border)',
            borderRadius: 16, resize: 'none', overflow: 'hidden',
            background: 'var(--surface)', color: 'var(--text)', outline: 'none',
            fontFamily: 'inherit', fontSize: 16, boxSizing: 'border-box',
            transition: 'border-color 0.15s',
          }}
        />
        {mediaFiles.length > 0 && (
            (() => {
              const urls = mediaFiles.map(m => m.previewUrl);
              const count = urls.length;
              const odd = count % 2 === 1 && count > 1;
              const columns = count === 1 ? '1fr' : 'repeat(2,1fr)';
              const removeMedia = (idx) => setMediaFiles(prev => prev.filter((_, i) => i !== idx));
              return (
                <div style={{ display: 'grid', gridTemplateColumns: columns, gap: 4, borderRadius: 12, overflow: 'hidden', border: '1px solid var(--border)', marginBottom: 8 }}>
                  {urls.map((url, i) => {
                    const isFirst = i === 0;
                    const style = { width: '100%', objectFit: 'cover', display: 'block' };
                    if (count === 1) style.aspectRatio = '16/9';
                    else if (odd && isFirst) { style.gridColumn = '1 / -1'; style.aspectRatio = '16/9'; }
                    else style.aspectRatio = '1';
                    return (
                      <div key={i} style={{ position: 'relative' }}>
                        <img src={url} alt="" style={style} />
                        <button onClick={() => removeMedia(i)} style={{
                          position: 'absolute', top: 4, right: 4,
                          background: 'rgba(0,0,0,0.6)', color: '#fff', border: 'none',
                          borderRadius: '50%', width: 22, height: 22, cursor: 'pointer',
                          fontSize: 14, lineHeight: 1, display: 'flex', alignItems: 'center', justifyContent: 'center',
                        }}>×</button>
                      </div>
                    );
                  })}
                </div>
              );
            })()
        )}
        {error && <div style={{ color: 'var(--like)', fontSize: 13, marginBottom: 6 }}>{error}</div>}
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 10 }}>
          <div>
            {mediaFiles.length < MAX_FILES && (
              <button onClick={() => inputRef.current?.click()} title="Прикрепить медиа" style={{ background: 'none', border: 'none', cursor: 'pointer', padding: 6, borderRadius: '50%', color: 'var(--accent)', display: 'inline-flex', alignItems: 'center' }}>
                <svg width={20} height={20} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
                  <rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/>
                  <polyline points="21 15 16 10 5 21"/>
                </svg>
              </button>
            )}
          </div>
          <div style={{ display: 'flex', justifyContent: 'flex-end', alignItems: 'center', gap: 10 }}>
          <span style={{ fontSize: 13, color: text.length > 1000 ? 'var(--like)' : 'var(--text-muted)' }}>
            {text.length > 0 ? `${text.length}/1000` : ''}
          </span>
          <button onClick={submit} disabled={(!text.trim() && mediaFiles.length === 0) || busy || text.length > 1000} style={{
            padding: '8px 18px', borderRadius: 9999, border: 'none',
            background: 'var(--accent)', color: '#fff', fontFamily: 'inherit',
            fontSize: 15, fontWeight: 700, cursor: ((text.trim() || mediaFiles.length) && !busy && text.length <= 1000) ? 'pointer' : 'not-allowed',
            opacity: ((text.trim() || mediaFiles.length) && !busy && text.length <= 1000) ? 1 : 0.5,
          }}>
            {busy ? '...' : 'Опубликовать'}
          </button>
          </div>
        </div>
        <input ref={inputRef} type="file" accept="image/*,video/*" multiple style={{ display: 'none' }} onChange={handleMediaSelect} />
      </div>
    </div>
  );
}

function UniversityPicker({ universities, currentId, onSelect, currentName, noUniversityActive = false }) {
  const [open, setOpen] = React.useState(false);
  const ref = React.useRef(null);
  React.useEffect(() => {
    if (!open) return;
    const h = (e) => { if (ref.current && !ref.current.contains(e.target)) setOpen(false); };
    document.addEventListener('mousedown', h);
    return () => document.removeEventListener('mousedown', h);
  }, [open]);

  return (
    <div ref={ref} style={{ position: 'relative' }}>
      <button onClick={() => setOpen(o => !o)} style={{
        display: 'flex', alignItems: 'center', gap: 6,
        background: 'transparent', border: 'none', cursor: 'pointer',
        padding: '6px 8px', borderRadius: 9999,
        color: 'var(--text)',
        fontFamily: 'inherit', fontSize: 20, fontWeight: 800,
      }}>
        {currentName}
        <ChevronDownIcon size={18} color="var(--text-muted)" />
      </button>
      {open && (
        <div style={{
          position: 'absolute', top: '100%', left: 0, marginTop: 6,
          background: 'var(--surface)', border: '1px solid var(--border)',
          borderRadius: 14, boxShadow: '0 8px 28px rgba(0,0,0,0.18)',
          minWidth: 260, padding: 8, zIndex: 20,
        }}>
          <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--text-muted)', padding: '6px 10px', textTransform: 'uppercase', letterSpacing: 0.5 }}>Ленты</div>
          <button onClick={() => { onSelect(null); setOpen(false); }} className="um-side-nav" style={pickerItemStyle(currentId === null && !noUniversityActive)}>
            <HomeIcon size={20} color="var(--text)" filled={currentId === null && !noUniversityActive} />
            <span>Главная</span>
          </button>
          <button onClick={() => { onSelect('__no_university__'); setOpen(false); }} className="um-side-nav" style={pickerItemStyle(noUniversityActive)}>
            <MessageBubbleIcon size={22} color="var(--text)" />
            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start' }}>
              <span style={{ fontWeight: 700, fontSize: 14 }}>Без ВУЗа</span>
              <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>Выбор, поступление и общие вопросы</span>
            </div>
          </button>
          <div style={{ height: 1, background: 'var(--border)', margin: '6px 0' }} />
          <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--text-muted)', padding: '6px 10px', textTransform: 'uppercase', letterSpacing: 0.5 }}>Университеты</div>
          {universities.map(u => (
            <button key={u.id} onClick={() => { onSelect(u.id); setOpen(false); }} className="um-side-nav" style={pickerItemStyle(u.id === currentId)}>
              <UniBadge uni={u} size={28} />
              <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start' }}>
                <span style={{ fontWeight: 700, fontSize: 14 }}>{u.name}</span>
                <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>{u.shortName}</span>
              </div>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
function pickerItemStyle(active) {
  return {
    display: 'flex', alignItems: 'center', gap: 12, width: '100%',
    background: active ? 'var(--accent-subtle)' : 'transparent',
    border: 'none', cursor: 'pointer', padding: '10px 12px', borderRadius: 10,
    fontFamily: 'inherit', fontSize: 14, color: 'var(--text)', textAlign: 'left',
  };
}

function FacultyProgramBar({ faculties, programs, facultyId, programId, onFaculty, onProgram }) {
  const chip = (label, active, onClick, tone = 'default') => (
    <button onClick={onClick} style={{
      padding: tone === 'action' ? '7px 14px' : '7px 13px',
      borderRadius: 9999,
      border: '1px solid ' + (active ? 'var(--accent)' : 'var(--border)'),
      background: active ? 'var(--accent)' : tone === 'action' ? 'var(--accent-subtle)' : 'var(--surface)',
      color: active ? '#fff' : tone === 'action' ? 'var(--accent)' : 'var(--text)',
      fontFamily: 'inherit',
      fontSize: 13.5,
      fontWeight: active || tone === 'action' ? 700 : 500,
      cursor: 'pointer',
      whiteSpace: 'nowrap',
      boxShadow: active ? '0 6px 16px rgba(29,155,240,.22)' : 'none',
    }}>{label}</button>
  );

  return (
    <div style={{ padding: '10px 16px 12px', borderTop: '1px solid var(--border)', background: 'var(--header-bg)' }}>
      <div className="um-topic-scroll" style={{ display: 'flex', gap: 8, overflowX: 'auto', padding: '2px 0 8px', scrollbarGutter: 'stable' }}>
        {chip('Все факультеты', !facultyId, () => { onFaculty(null); onProgram(null); })}
        {faculties.map(f => chip(f.shortName || f.name, facultyId === f.id, () => onFaculty(f.id)))}
      </div>
      {facultyId && (
        <div className="um-topic-scroll" style={{ display: 'flex', gap: 8, overflowX: 'auto', padding: '4px 0 2px', scrollbarGutter: 'stable' }}>
          {chip('Все программы', !programId, () => onProgram(null))}
          {programs.map(p => chip(p.shortName || p.name, programId === p.id, () => onProgram(p.id), 'subtle'))}
        </div>
      )}
    </div>
  );
}

function TopicChipBar({ topics, currentId, onSelect }) {

  const [expanded, setExpanded] = React.useState(null);

  React.useEffect(() => {
    if (!currentId) { setExpanded(null); return; }
    const cur = topics.flatMap(t => [t, ...(t.subtopics || [])]).find(x => x.id === currentId);
    if (!cur) return;
    const parent = topics.find(t => (t.subtopics || []).some(s => s.id === currentId));
    if (parent) setExpanded(parent.id);
  }, [currentId, topics]);

  const chip = (label, active, onClick, subtle) => (
    <button onClick={onClick} style={{
      padding: '6px 14px', borderRadius: 9999,
      border: '1px solid ' + (active ? 'var(--accent)' : 'var(--border-strong)'),
      background: active ? 'var(--accent)' : (subtle ? 'var(--surface-2)' : 'transparent'),
      color: active ? '#fff' : 'var(--text)',
      fontFamily: 'inherit', fontSize: 13.5, fontWeight: active ? 700 : 500,
      cursor: 'pointer', whiteSpace: 'nowrap',
      transition: 'all 0.12s',
    }}>{label}</button>
  );

  const expandedTopic = expanded ? topics.find(t => t.id === expanded) : null;

  return (
    <div style={{ padding: '10px 16px 12px', borderTop: '1px solid var(--border)', background: 'var(--header-bg)' }}>
      <div className="um-topic-scroll" style={{ display: 'flex', gap: 8, overflowX: 'auto', padding: '2px 0 10px', scrollbarGutter: 'stable' }}>
        {chip('Все', currentId === null, () => { onSelect(null); setExpanded(null); })}
        {topics.map(t => {
          const active = currentId === t.id || (t.subtopics || []).some(s => s.id === currentId);
          return chip(
            <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
              {t.isSystem ? '#' : ''}{t.name}
              {(t.subtopics || []).length > 0 && (
                <ChevronDownIcon size={12} color={active ? '#fff' : 'var(--text-muted)'} />
              )}
            </span>,
            active,
            () => {
              if ((t.subtopics || []).length > 0) {
                onSelect(t.id);
                setExpanded(expanded === t.id ? null : t.id);
              } else {
                onSelect(t.id);
                setExpanded(null);
              }
            }
          );
        })}
      </div>
      {expandedTopic && (expandedTopic.subtopics || []).length > 0 && (
        <div className="um-topic-scroll" style={{ display: 'flex', gap: 8, overflowX: 'auto', padding: '8px 0 4px', scrollbarGutter: 'stable' }}>
          {chip('Все ' + expandedTopic.name, currentId === expandedTopic.id, () => onSelect(expandedTopic.id), true)}
          {expandedTopic.subtopics.map(s =>
            chip(s.name, currentId === s.id, () => onSelect(s.id), true)
          )}
        </div>
      )}
    </div>
  );
}

function InfiniteSentinel({ onIntersect, loading }) {
  const ref = React.useRef(null);
  const cbRef = React.useRef(onIntersect);
  React.useEffect(() => { cbRef.current = onIntersect; }, [onIntersect]);
  React.useEffect(() => {
    const node = ref.current;
    if (!node) return;
    const observer = new IntersectionObserver(entries => {
      for (const e of entries) {
        if (e.isIntersecting) cbRef.current && cbRef.current();
      }
    }, { rootMargin: '600px 0px' });
    observer.observe(node);
    return () => observer.disconnect();
  }, []);
  return (
    <div ref={ref} style={{ padding: 24, textAlign: 'center', color: 'var(--text-muted)', fontSize: 13 }}>
      {loading ? <Spinner size={18} /> : ''}
    </div>
  );
}

Object.assign(window, { FeedPage, InlineCompose, UniversityPicker, FacultyProgramBar, InfiniteSentinel });
