

function ImageLightbox({ urls, startIndex, onClose }) {
  const [idx, setIdx] = React.useState(startIndex);
  React.useEffect(() => {
    const h = (e) => { if (e.key === 'Escape') onClose(); if (e.key === 'ArrowRight') setIdx(i => Math.min(i + 1, urls.length - 1)); if (e.key === 'ArrowLeft') setIdx(i => Math.max(i - 1, 0)); };
    document.addEventListener('keydown', h);
    return () => document.removeEventListener('keydown', h);
  }, [urls.length, onClose]);
  return ReactDOM.createPortal(
    <div onClick={onClose} style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.92)', zIndex: 9000, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <button onClick={onClose} style={{ position: 'absolute', top: 16, right: 16, background: 'rgba(255,255,255,0.12)', border: 'none', borderRadius: '50%', width: 40, height: 40, color: '#fff', fontSize: 22, cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>×</button>
      {urls.length > 1 && idx > 0 && (
        <button onClick={e => { e.stopPropagation(); setIdx(i => i - 1); }} style={{ position: 'absolute', left: 16, background: 'rgba(255,255,255,0.12)', border: 'none', borderRadius: '50%', width: 44, height: 44, color: '#fff', fontSize: 24, cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>‹</button>
      )}
      {urls.length > 1 && idx < urls.length - 1 && (
        <button onClick={e => { e.stopPropagation(); setIdx(i => i + 1); }} style={{ position: 'absolute', right: 16, background: 'rgba(255,255,255,0.12)', border: 'none', borderRadius: '50%', width: 44, height: 44, color: '#fff', fontSize: 24, cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>›</button>
      )}
      <img src={urls[idx]} alt="" onClick={e => e.stopPropagation()} style={{ maxWidth: 'calc(100vw - 96px)', maxHeight: 'calc(100vh - 80px)', objectFit: 'contain', borderRadius: 8 }} />
      {urls.length > 1 && (
        <div style={{ position: 'absolute', bottom: 16, left: '50%', transform: 'translateX(-50%)', display: 'flex', gap: 6 }}>
          {urls.map((_, i) => <div key={i} style={{ width: 7, height: 7, borderRadius: '50%', background: i === idx ? '#fff' : 'rgba(255,255,255,0.4)' }} />)}
        </div>
      )}
    </div>,
    document.body
  );
}

function PostCard({ post, onNavigate, currentUser, onLike }) {
  const [author, setAuthor] = React.useState(post.author || null);
  const [liked, setLiked] = React.useState(post.likedByMe);
  const [likes, setLikes] = React.useState(post.likesCount);
  const [likePulse, setLikePulse] = React.useState(false);
  const [copied, setCopied] = React.useState(false);
  const [hoverCardOpen, setHoverCardOpen] = React.useState(false);
  const [hoverCardClosing, setHoverCardClosing] = React.useState(false);
  const [hoverPos, setHoverPos] = React.useState({ top: 0, left: 0 });
  const [lightboxIdx, setLightboxIdx] = React.useState(null);
  const hoverTimer = React.useRef(null);
  const closeTimer = React.useRef(null);
  const canHover = React.useMemo(() => window.matchMedia('(hover: hover)').matches, []);

  React.useEffect(() => {
    if (post.author) { setAuthor(post.author); return; }
    API.getCachedUser(post.authorId).then(setAuthor);
  }, [post.authorId, post.author]);
  React.useEffect(() => { setLiked(post.likedByMe); setLikes(post.likesCount); }, [post.likedByMe, post.likesCount]);

  const likeInFlight = React.useRef(false);
  const handleLike = async (e) => {
    e.stopPropagation();
    if (!currentUser) { onNavigate('/login'); return; }
    if (likeInFlight.current) return;
    likeInFlight.current = true;
    const wasLiked = liked;
    const previousLikes = likes || 0;
    setLiked(!wasLiked);
    setLikes(Math.max(0, previousLikes + (wasLiked ? -1 : 1)));
    try {
      if (wasLiked) {
        await API.gql(API.M.unlikePost, { postId: post.id });
      } else {
        await API.gql(API.M.likePost, { postId: post.id });
        setLikePulse(false);
        requestAnimationFrame(() => setLikePulse(true));
        setTimeout(() => setLikePulse(false), 380);
      }
      try {
        const fresh = await API.gql(API.Q.getPost, { id: post.id });
        if (fresh?.getPost) {
          setLiked(!!fresh.getPost.likedByMe);
          setLikes(fresh.getPost.likesCount ?? 0);
        }
      } catch {}
      onLike && onLike(post.id, !wasLiked);
    } catch (err) {
      setLiked(wasLiked);
      setLikes(previousLikes);
      if (err.isUnauth) onNavigate('/login');
    } finally { likeInFlight.current = false; }
  };

  const handleShare = async (e) => {
    e.stopPropagation();
    const url = window.location.origin + window.location.pathname + '#/post/' + post.id;
    try {
      await navigator.clipboard.writeText(url);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1400);
    } catch {}
  };

  const onAuthorHoverStart = (e) => {
    if (!canHover || !author) return;
    clearTimeout(hoverTimer.current);
    clearTimeout(closeTimer.current);
    const rect = e.currentTarget.getBoundingClientRect();
    hoverTimer.current = setTimeout(() => {
      setHoverPos({
        top: Math.min(rect.bottom + 8, window.innerHeight - 240),
        left: Math.max(8, Math.min(rect.left - 20, window.innerWidth - 296)),
      });
      setHoverCardClosing(false);
      setHoverCardOpen(true);
    }, 450);
  };
  const scheduleHoverClose = () => {
    clearTimeout(closeTimer.current);
    if (!hoverCardOpen && !hoverCardClosing) return;
    closeTimer.current = setTimeout(() => {
      setHoverCardClosing(true);
      closeTimer.current = setTimeout(() => {
        setHoverCardOpen(false);
        setHoverCardClosing(false);
      }, 150);
    }, 1000);
  };
  const onAuthorHoverEnd = () => {
    clearTimeout(hoverTimer.current);
    scheduleHoverClose();
  };
  const onHoverCardEnter = () => {
    clearTimeout(closeTimer.current);
    setHoverCardClosing(false);
  };
  const onHoverCardLeave = () => {
    scheduleHoverClose();
  };

  const timeAgo = useTimeAgo(post.createdAt);
  const displayName = author
    ? (author.name && author.surname ? `${author.name} ${author.surname}` : author.username || 'Пользователь')
    : ' ';

  return (
    <>
    <article
      className="um-feed-row"
      onClick={() => onNavigate('/post/' + post.id)}
      style={{
        display: 'flex', gap: 12, padding: '12px 16px',
        borderBottom: '1px solid var(--border)',
        cursor: 'pointer', background: 'var(--bg)',
      }}>

      <div style={{ flexShrink: 0, width: 42, height: 42, alignSelf: 'flex-start' }}
        onClick={e => { e.stopPropagation(); author && onNavigate(API.profileUrl(author)); }}
        onMouseEnter={onAuthorHoverStart}
        onMouseLeave={onAuthorHoverEnd}>
        <Avatar user={author} size={42} />
      </div>


      <div style={{ flex: 1, minWidth: 0 }}>

        <div style={{ display: 'flex', alignItems: 'center', gap: 6, color: 'var(--text-muted)', fontSize: 14.5, lineHeight: 1.3 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 4, flex: 1, minWidth: 0, overflow: 'hidden' }}>
            <span
              style={{ fontWeight: 700, color: 'var(--text)', cursor: 'pointer', position: 'relative', flexShrink: 1, minWidth: 0, overflow: 'hidden', whiteSpace: 'nowrap', textOverflow: 'ellipsis' }}
              onClick={e => { e.stopPropagation(); author && onNavigate(API.profileUrl(author)); }}
              onMouseEnter={onAuthorHoverStart}
              onMouseLeave={onAuthorHoverEnd}>
              {displayName}
            </span>
            {author?.isStudentVerified && <span title="Студент верифицирован" style={{ flexShrink: 0, display: 'inline-flex' }}><CheckIcon size={15} color="var(--accent)" /></span>}
            {author?.isEmployeeVerified && <span title="Преподаватель/сотрудник верифицирован" style={{ flexShrink: 0, display: 'inline-flex' }}><TeacherIcon size={15} color="oklch(0.55 0.18 150)" /></span>}
            {author?.username && <span style={{ flexShrink: 1, minWidth: 0, overflow: 'hidden', whiteSpace: 'nowrap', textOverflow: 'ellipsis' }}>@{author.username}</span>}
            <span aria-hidden style={{ flexShrink: 0 }}>·</span>
            <span style={{ flexShrink: 0, whiteSpace: 'nowrap' }}>{timeAgo}</span>
          </div>
            {post.universityId && author?.university && String(author.university.id) === String(post.universityId) && (
            <div style={{ flexShrink: 0 }}>
              <UniBadge uni={author.university} size={22} />
            </div>
          )}
        </div>


        <div style={{
          marginTop: 2,
          fontSize: 15, lineHeight: 1.45, color: 'var(--text)',
          whiteSpace: 'pre-wrap', wordBreak: 'break-word',
        }}>
          {post.content}
        </div>


        {post.mediaUrls?.length > 0 && (
          (() => {
            const urls = post.mediaUrls.slice(0, 4).map(url => API.resolveAssetUrl(url));
            const count = urls.length;
            const odd = count % 2 === 1 && count > 1;
            const columns = count === 1 ? '1fr' : 'repeat(2, 1fr)';
            return (
              <div style={{
                display: 'grid', marginTop: 12,
                gridTemplateColumns: columns,
                gap: 2, borderRadius: 16, overflow: 'hidden',
                border: '1px solid var(--border)',
              }}>
                {urls.map((url, i) => {
                  const isFirst = i === 0;
                  const style = {
                    width: '100%',
                    objectFit: 'cover',
                    display: 'block',
                    background: 'var(--surface-2)',
                    cursor: 'zoom-in',
                  };
                  if (count === 1) style.maxHeight = 480;
                  else if (odd && isFirst) { style.gridColumn = '1 / -1'; style.aspectRatio = '16/9'; }
                  else style.aspectRatio = '1';
                  return (
                    <img key={i} src={url} alt="" onClick={e => { e.stopPropagation(); setLightboxIdx(i); }} style={style} />
                  );
                })}
              </div>
            );
          })()
        )}


        <div className='um-action-bar' style={{ display: 'flex', justifyContent: 'space-between', maxWidth: 420, marginTop: 10, marginLeft: -8 }}>
          <ActionBtn className="um-reply-btn"
            onClick={e => { e.stopPropagation(); onNavigate('/post/' + post.id); }}
            icon={<CommentIcon size={18} color="var(--text-muted)" />}
            label={post.commentsCount} />
          <ActionBtn
            className={`um-like-btn ${likePulse ? 'um-likebtn-active' : ''}`}
            onClick={handleLike} active={liked}
            activeColor="var(--like)"
            icon={<HeartIcon size={18} color={liked ? 'var(--like)' : 'var(--text-muted)'} filled={liked} />}
            label={likes} />
          <ActionBtn className="um-icon-btn" onClick={handleShare}
            icon={<ShareIcon size={18} color="var(--text-muted)" />}
            label="" />
        </div>

      </div>
    </article>
    {copied && ReactDOM.createPortal(
      <div style={{
        position: 'fixed', bottom: 88, left: '50%', transform: 'translateX(-50%)',
        background: 'var(--text)', color: 'var(--bg)',
        padding: '8px 20px', borderRadius: 9999,
        fontSize: 13, fontWeight: 600,
        zIndex: 2000, animation: 'fadein-toast 0.15s ease',
        boxShadow: '0 4px 16px rgba(0,0,0,0.22)',
        pointerEvents: 'none', whiteSpace: 'nowrap',
      }}>Ссылка скопирована</div>,
      document.body
    )}
    {(hoverCardOpen || hoverCardClosing) && author && (
      <ProfileHoverCard user={author} pos={hoverPos} onNavigate={onNavigate} onClose={() => setHoverCardOpen(false)} onMouseEnter={onHoverCardEnter} onMouseLeave={onHoverCardLeave} closing={hoverCardClosing} />
    )}
    {lightboxIdx !== null && post.mediaUrls?.length > 0 && (
      <ImageLightbox urls={post.mediaUrls.map(url => API.resolveAssetUrl(url))} startIndex={lightboxIdx} onClose={() => setLightboxIdx(null)} />
    )}
    </>
  );
}

function ActionBtn({ onClick, icon, label, active, loading, activeColor, className }) {
  return (
    <button
      className={className}
      onClick={onClick}
      style={{
        display: 'flex', alignItems: 'center', gap: 6,
        background: 'none', border: 'none',
        color: active ? activeColor : 'var(--text-muted)',
        cursor: onClick ? 'pointer' : 'default',
        fontFamily: 'inherit', fontSize: 13.5,
        padding: '6px 10px', borderRadius: 9999,
        transition: 'background 0.12s, color 0.12s',
      }}>
      <span style={{ display: 'inline-flex', alignItems: 'center', justifyContent: 'center', width: 24, height: 24 }}>
        {loading ? <Spinner size={16} color="var(--text-muted)" /> : icon}
      </span>
      {label !== '' && label !== undefined && label !== null && <span>{label}</span>}
    </button>
  );
}


function ComposeModal({ open, onClose, currentUser, onNavigate, onCreated, defaultTopicId }) {
  const [content, setContent] = React.useState('');
  const [topicId, setTopicId] = React.useState(defaultTopicId || '');
  const [loading, setLoading] = React.useState(false);
  const [error, setError] = React.useState('');
  const [mediaFiles, setMediaFiles] = React.useState([]);
  const [uploading, setUploading] = React.useState(false);
  const mediaInputRef = React.useRef();
  const textareaRef = React.useRef();
  const MAX_MEDIA_SIZE = 10 * 1024 * 1024;
  const MAX_FILES = 10;

  React.useEffect(() => {
    const ta = textareaRef.current;
    if (!ta) return;
    ta.style.height = 'auto';
    ta.style.height = Math.max(120, ta.scrollHeight) + 'px';
  }, [content]);

  React.useEffect(() => {
    if (open) { setContent(''); setError(''); setTopicId(defaultTopicId || ''); setMediaFiles([]); }
  }, [open, defaultTopicId]);

  const handleMediaSelect = (e) => {
    const files = Array.from(e.target.files || []);
    e.target.value = '';
    const remaining = MAX_FILES - mediaFiles.length;
    const toAdd = files.slice(0, remaining);
    if (files.length > remaining) { setError('Можно прикрепить не больше 10 файлов'); return; }
    if (toAdd.some(file => !file.type.startsWith('image/') && file.size > MAX_MEDIA_SIZE)) {
      setError('Файл слишком большой (максимум 10 МБ)');
      return;
    }
    setError('');
    toAdd.forEach(file => {
      const reader = new FileReader();
      reader.onload = (ev) => setMediaFiles(prev => [...prev, { file, previewUrl: ev.target.result }]);
      reader.readAsDataURL(file);
    });
  };

  const removeMedia = (idx) => setMediaFiles(prev => prev.filter((_, i) => i !== idx));

  const submit = async () => {
    if (loading || uploading) return;
    const submittedContent = content.trim();
    if (!submittedContent) { setError('Добавьте текст записи'); return; }
    if (content.length > 1000) { setError('Сократите запись до 1000 символов'); return; }
    setLoading(true); setUploading(mediaFiles.length > 0); setError('');
    const submittedMediaFiles = mediaFiles;
    const submittedTopicId = topicId;
    setContent('');
    setMediaFiles([]);
    onClose();
    try {
      const tmpId = 'tmp-' + Date.now();
      const clientRequestId = API.newClientRequestId();
      const optimisticMedia = submittedMediaFiles.map(m => m.previewUrl);
      const optimisticPost = {
        id: tmpId,
        author: currentUser,
        authorId: currentUser?.id,
        content: submittedContent,
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
        let mediaUrls = [];
        if (submittedMediaFiles.length > 0) {
          mediaUrls = await Promise.all(submittedMediaFiles.map(m => API.uploadFile(m.file, 'post-media')));
        }
        setUploading(false);
        const input = { content: submittedContent, mediaUrls };
        if (submittedTopicId) input.topicId = submittedTopicId;
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
        throw innerErr;
      }
    } catch (e) {
      if (e.isUnauth) { onNavigate('/login'); }
      else setError(API.userMessage ? API.userMessage(e.message) : e.message);
    } finally { setLoading(false); setUploading(false); }
  };

  if (!open) return null;
  const len = content.length;
  const tooLong = len > 1000;

  return (
    <div onClick={onClose} style={{
      position: 'fixed', inset: 0, background: 'rgba(91,112,131,0.4)', backdropFilter: 'blur(4px)',
      zIndex: 1000, display: 'flex', alignItems: 'flex-start', justifyContent: 'center', padding: '40px 16px',
    }}>
      <div onClick={e => e.stopPropagation()} style={{
        background: 'var(--bg)', borderRadius: 16,
        width: '100%', maxWidth: 600, padding: '18px 20px 20px',
        boxShadow: '0 0 30px rgba(0,0,0,0.15)',
      }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8 }}>
          <button onClick={onClose} style={{
            background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text)',
            fontSize: 20, padding: 8, borderRadius: '50%', lineHeight: 1,
          }}>×</button>
        </div>
        <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start', padding: '10px 8px 12px' }}>
          <div style={{ position: 'relative' }}>
            <Avatar user={currentUser} size={42} />
            {currentUser?.university && (
              <div style={{
                position: 'absolute', bottom: -2, right: -2,
                width: 20, height: 20, borderRadius: 6,
                background: 'var(--bg)', border: '2px solid var(--bg)',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                overflow: 'hidden',
              }}>
                <UniBadge uni={currentUser.university} size={16} />
              </div>
            )}
          </div>
          <div style={{ flex: 1 }}>
            <textarea
              ref={textareaRef}
              value={content} onChange={e => setContent(e.target.value)}
              placeholder="Что у вас нового?"
              autoFocus
              rows={4}
              style={{
                width: '100%', minHeight: 120, padding: '12px 14px',
                border: '1.5px solid var(--border)', borderRadius: 16,
                background: 'var(--surface)', color: 'var(--text)',
                fontFamily: 'inherit', fontSize: 17, resize: 'none', outline: 'none',
                overflow: 'hidden', boxSizing: 'border-box',
                transition: 'border-color 0.15s',
              }}
            />

            {mediaFiles.length > 0 && (
              (() => {
                const urls = mediaFiles.map(m => m.previewUrl);
                const count = urls.length;
                const odd = count % 2 === 1 && count > 1;
                const columns = count === 1 ? '1fr' : 'repeat(2, 1fr)';
                return (
                  <div style={{
                    display: 'grid',
                    gridTemplateColumns: columns,
                    gap: 4, borderRadius: 12, overflow: 'hidden', marginBottom: 8,
                    border: '1px solid var(--border)',
                  }}>
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
            {error && <div style={{ color: 'var(--like)', fontSize: 13, margin: '6px 0' }}>{error}</div>}
          </div>
        </div>
        <div style={{ borderTop: '1px solid var(--border)', marginTop: 8, padding: '14px 8px 0',
          display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            {mediaFiles.length < MAX_FILES && (
              <button onClick={() => mediaInputRef.current?.click()} title="Прикрепить медиа"
                style={{ background: 'none', border: 'none', cursor: 'pointer', padding: 6, borderRadius: '50%', color: 'var(--accent)', display: 'flex', alignItems: 'center' }}>
                <svg width={20} height={20} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
                  <rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/>
                  <polyline points="21 15 16 10 5 21"/>
                </svg>
              </button>
            )}
            <div style={{ fontSize: 13, color: tooLong ? 'var(--like)' : 'var(--text-muted)' }}>{len}/1000</div>
          </div>
          <Button onClick={submit} loading={loading || uploading} disabled={(!content.trim() && mediaFiles.length === 0) || tooLong}>
            {uploading ? 'Загрузка...' : 'Опубликовать'}
          </Button>
        </div>
        <input ref={mediaInputRef} type="file" accept="image/*,video/*" multiple style={{ display: 'none' }} onChange={handleMediaSelect} />
      </div>
    </div>
  );
}

Object.assign(window, { PostCard, ComposeModal, ActionBtn, ImageLightbox });
