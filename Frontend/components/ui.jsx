


function Avatar({ user, size = 40 }) {
  const [fallback, setFallback] = React.useState(false);
  const initials = user
    ? ((user.name?.[0] || '') + (user.surname?.[0] || '')) || user.username?.[0] || '?'
    : '?';
  const colors = ['#A8DADC', '#B8E0D2', '#D8B4E2', '#F3C4A8', '#F7D6E0', '#C9D6FF', '#F9E2AF', '#BEE3DB'];
  const hashSeed = (user?.username || user?.id || 'anonymous');
  const hash = [...hashSeed].reduce((acc, ch) => ((acc * 33) + ch.charCodeAt(0)) >>> 0, 5381);
  const bg = colors[hash % colors.length];

  const avatarUrl = !fallback ? (user?.avatarUrl || '/default_pic.png') : null;
  const style = {
    width: size, height: size, borderRadius: '50%',
    background: avatarUrl ? 'transparent' : bg,
    display: 'flex', alignItems: 'center', justifyContent: 'center',
    fontWeight: 600, color: '#fff', fontSize: size * 0.38,
    flexShrink: 0, overflow: 'hidden',
    fontFamily: 'inherit',
  };
  if (avatarUrl) {
    return (
      <div style={style}>
        <img src={API.resolveAssetUrl(avatarUrl)} alt="" onError={() => setFallback(true)}
          style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
      </div>
    );
  }
  return <div style={style}>{initials.toUpperCase()}</div>;
}

function Button({ children, variant = 'primary', size = 'md', onClick, disabled, loading, full, type = 'button', style: extraStyle }) {
  const base = {
    display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
    gap: 6, border: 'none', borderRadius: 10, cursor: disabled || loading ? 'not-allowed' : 'pointer',
    fontFamily: 'inherit', fontWeight: 600, transition: 'all 0.15s',
    opacity: disabled || loading ? 0.6 : 1,
    width: full ? '100%' : undefined,
  };
  const sizes = {
    sm: { padding: '6px 14px', fontSize: 13 },
    md: { padding: '10px 20px', fontSize: 14 },
    lg: { padding: '13px 28px', fontSize: 15 },
  };
  const variants = {
    primary: { background: 'var(--accent)', color: '#fff' },
    secondary: { background: 'var(--surface-2)', color: 'var(--text)' },
    ghost: { background: 'transparent', color: 'var(--text-muted)' },
    danger: { background: 'oklch(0.55 0.22 15)', color: '#fff' },
    outline: { background: 'transparent', border: '1.5px solid var(--border)', color: 'var(--text)' },
  };
  return (
    <button type={type} onClick={onClick} disabled={disabled || loading} style={{ ...base, ...sizes[size], ...variants[variant], ...extraStyle }}>
      {loading ? <Spinner size={14} color="currentColor" /> : null}
      {children}
    </button>
  );
}

function Input({ label, value, onChange, placeholder, type = 'text', multiline, rows = 4, error, hint, name, autoFocus, ...props }) {
  const inputStyle = {
    width: '100%', padding: '10px 14px', borderRadius: 10, boxSizing: 'border-box',
    border: '1.5px solid ' + (error ? 'oklch(0.55 0.22 15)' : 'var(--border)'),
    background: 'var(--surface)', color: 'var(--text)',
    fontFamily: 'inherit', fontSize: 14, outline: 'none',
    transition: 'border-color 0.15s', resize: multiline ? 'vertical' : undefined,
  };
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
      {label && <label style={{ fontSize: 13, fontWeight: 600, color: 'var(--text-muted)' }}>{label}</label>}
      {multiline
        ? <textarea name={name} value={value} onChange={onChange} placeholder={placeholder} rows={rows} style={inputStyle} autoFocus={autoFocus} {...props} />
        : <input name={name} type={type} value={value} onChange={onChange} placeholder={placeholder} style={inputStyle} autoFocus={autoFocus} {...props} />
      }
      {error && <span style={{ fontSize: 12, color: 'oklch(0.55 0.22 15)' }}>{error}</span>}
      {hint && !error && <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>{hint}</span>}
    </div>
  );
}

function Spinner({ size = 20, color = 'var(--accent)' }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" style={{ animation: 'spin 0.8s linear infinite', display: 'block' }}>
      <circle cx="12" cy="12" r="10" fill="none" stroke={color} strokeWidth="2.5" strokeDasharray="31.4" strokeDashoffset="10" strokeLinecap="round" />
    </svg>
  );
}

function EmptyBoxIcon({ size = 40, color = 'currentColor' }) {
  return (
    <svg width={size} height={size} viewBox="0 0 48 48" fill="none" stroke={color} strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round">
      <path d="M8 18h32l-4 20H12L8 18Z" />
      <path d="M14 18l4-8h12l4 8" />
      <path d="M18 26h12" />
    </svg>
  );
}

function CatFaceIcon({ size = 40, color = 'currentColor' }) {
  return (
    <svg width={size} height={size} viewBox="0 0 48 48" fill="none" stroke={color} strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round">
      <path d="M13 20V10l8 7h6l8-7v10" />
      <path d="M10 24c0 10 6 16 14 16s14-6 14-16c0-7-5-11-14-11S10 17 10 24Z" />
      <path d="M18 26h.01M30 26h.01" />
      <path d="M21 32c2 1.5 4 1.5 6 0" />
    </svg>
  );
}

function MessageBubbleIcon({ size = 40, color = 'currentColor' }) {
  return (
    <svg width={size} height={size} viewBox="0 0 48 48" fill="none" stroke={color} strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round">
      <path d="M10 14h28v20H19l-9 6V14Z" />
      <path d="M18 22h12M18 28h8" />
    </svg>
  );
}

function Badge({ children, color = 'accent' }) {
  const colors = {
    accent: { bg: 'var(--accent-subtle)', text: 'var(--accent)' },
    green: { bg: 'oklch(0.93 0.08 150)', text: 'oklch(0.4 0.15 150)' },
    muted: { bg: 'var(--surface-2)', text: 'var(--text-muted)' },
  };
  const c = colors[color] || colors.accent;
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, padding: '2px 9px', borderRadius: 20, fontSize: 11, fontWeight: 700, background: c.bg, color: c.text }}>
      {children}
    </span>
  );
}

function Modal({ open, onClose, title, children, width = 480 }) {
  if (!open) return null;
  return (
    <div onClick={onClose} style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.4)', backdropFilter: 'blur(4px)', zIndex: 1000, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 16 }}>
      <div onClick={e => e.stopPropagation()} style={{ background: 'var(--surface)', borderRadius: 16, width: '100%', maxWidth: width, maxHeight: '90vh', overflow: 'auto', padding: 24 }}>
        {title && (
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
            <h3 style={{ margin: 0, fontSize: 18, fontWeight: 700 }}>{title}</h3>
            <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-muted)', fontSize: 20, lineHeight: 1, padding: 4 }}>×</button>
          </div>
        )}
        {children}
      </div>
    </div>
  );
}

function Divider() {
  return <div style={{ height: 1, background: 'var(--border)', margin: '4px 0' }} />;
}

function EmptyState({ icon, title, subtitle }) {
  return (
    <div style={{ textAlign: 'center', padding: '56px 24px', color: 'var(--text-muted)' }}>
      <div style={{ fontSize: 40, marginBottom: 12, display: 'flex', justifyContent: 'center' }}>{icon}</div>
      <div style={{ fontWeight: 700, fontSize: 16, color: 'var(--text)', marginBottom: 6 }}>{title}</div>
      {subtitle && <div style={{ fontSize: 14 }}>{subtitle}</div>}
    </div>
  );
}

function normalizeIsoDate(isoStr) {
  if (!isoStr || typeof isoStr !== 'string') return isoStr;
  if (/[zZ]|[+-]\d{2}:\d{2}$/.test(isoStr)) return isoStr;
  if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?$/.test(isoStr)) {
    return isoStr + 'Z';
  }
  return isoStr;
}

function isEditedTimestamp(createdAt, updatedAt) {
  if (!createdAt || !updatedAt) return false;
  const created = new Date(normalizeIsoDate(createdAt)).getTime();
  const updated = new Date(normalizeIsoDate(updatedAt)).getTime();
  if (!Number.isFinite(created) || !Number.isFinite(updated)) return false;
  return updated - created > 1000;
}

function useTimeAgo(isoStr) {
  if (!isoStr) return '';
  const date = new Date(normalizeIsoDate(isoStr));
  if (Number.isNaN(date.getTime())) return '';
  const diff = (Date.now() - date.getTime()) / 1000;
  if (diff < 60) return 'только что';
  if (diff < 3600) return Math.floor(diff / 60) + ' мин';
  if (diff < 86400) return Math.floor(diff / 3600) + ' ч';
  if (diff < 604800) return Math.floor(diff / 86400) + ' дн';
  return date.toLocaleDateString('ru-RU', { day: 'numeric', month: 'short' });
}

function PostSkeleton() {
  const bar = (w, delay = 0) => ({
    height: 13, width: w, borderRadius: 6, background: 'var(--surface-2)',
    marginBottom: 8, animation: `skeleton 1.5s ease ${delay}s infinite`,
  });
  return (
    <div style={{ display: 'flex', gap: 12, padding: '12px 16px', borderBottom: '1px solid var(--border)' }}>
      <div style={{ width: 42, height: 42, borderRadius: '50%', background: 'var(--surface-2)', flexShrink: 0, animation: 'skeleton 1.5s ease infinite' }} />
      <div style={{ flex: 1 }}>
        <div style={bar('38%', 0)} />
        <div style={bar('92%', 0.1)} />
        <div style={bar('78%', 0.2)} />
        <div style={{ ...bar('55%', 0.25), marginBottom: 0 }} />
      </div>
    </div>
  );
}

function ProfileHoverCard({ user, pos, onNavigate, onClose, onMouseEnter, onMouseLeave, closing }) {
  if (!user) return null;
  const [visible, setVisible] = React.useState(false);

  React.useEffect(() => {
    const frame = window.requestAnimationFrame(() => setVisible(true));
    return () => window.cancelAnimationFrame(frame);
  }, []);

  const displayName = (user.name && user.surname) ? `${user.name} ${user.surname}` : user.name || user.username;
  const card = (
    <div
      onMouseEnter={onMouseEnter}
      onMouseLeave={onMouseLeave}
      style={{
        position: 'fixed', top: pos.top, left: pos.left, width: 280,
        background: 'var(--bg)', borderRadius: 16,
        border: '1px solid var(--border)', boxShadow: '0 8px 32px rgba(0,0,0,0.22)',
        padding: 16, zIndex: 1200,
        opacity: visible && !closing ? 1 : 0,
        transform: visible && !closing ? 'translateY(0)' : 'translateY(-4px)',
        transition: 'opacity 0.15s ease, transform 0.15s ease',
      }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 10 }}>
        <div style={{ cursor: 'pointer' }} onClick={() => { onClose(); onNavigate(API.profileUrl(user)); }}>
          <Avatar user={user} size={52} />
        </div>
        <Button size="sm" onClick={() => { onClose(); onNavigate(API.profileUrl(user)); }}>Открыть</Button>
      </div>
      <div style={{ fontWeight: 800, fontSize: 15, lineHeight: 1.2 }}>{displayName}</div>
      {user.username && <div style={{ color: 'var(--text-muted)', fontSize: 13 }}>@{user.username}</div>}
      {user.bio && (
        <div style={{ fontSize: 13, marginTop: 8, lineHeight: 1.4, color: 'var(--text)' }}>
          {user.bio.length > 110 ? user.bio.slice(0, 110) + '…' : user.bio}
        </div>
      )}
      {user.university?.name && (
        <div style={{ fontSize: 12, color: 'var(--text-muted)', marginTop: 6, display: 'flex', alignItems: 'center', gap: 5 }}>
          <UniIcon size={13} /> {user.university.name}
        </div>
      )}
    </div>
  );
  return ReactDOM.createPortal(card, document.body);
}

function ImageUploadField({ label, value, onChange, accept = 'image/*', preview = 'square', bucket = 'post-media' }) {
  const inputRef = React.useRef();
  const [uploading, setUploading] = React.useState(false);
  const [err, setErr] = React.useState('');

  const handleFile = async (e) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setErr(''); setUploading(true);
    try {
      const url = await API.uploadFile(file, bucket);
      onChange(url);
    } catch (ex) { setErr(ex.message || 'Ошибка загрузки'); }
    finally { setUploading(false); e.target.value = ''; }
  };

  const previewStyle = preview === 'circle'
    ? { width: 52, height: 52, borderRadius: '50%', objectFit: 'cover', border: '1px solid var(--border)', flexShrink: 0 }
    : { width: 80, height: 44, borderRadius: 8, objectFit: 'cover', border: '1px solid var(--border)', flexShrink: 0 };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
      {label && <label style={{ fontSize: 13, fontWeight: 600, color: 'var(--text-muted)' }}>{label}</label>}
      <div style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
        {value && <img src={value} alt="" style={previewStyle} />}
        <Button size="sm" variant="secondary" onClick={() => inputRef.current?.click()} loading={uploading}>
          {value ? 'Заменить' : 'Выбрать файл'}
        </Button>
        {value && (
          <button onClick={() => onChange('')} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-muted)', fontSize: 20, lineHeight: 1, padding: 2 }}>×</button>
        )}
      </div>
      {err && <div style={{ fontSize: 12, color: 'var(--like)' }}>{err}</div>}
      <input ref={inputRef} type="file" accept={accept} style={{ display: 'none' }} onChange={handleFile} />
    </div>
  );
}

Object.assign(window, {
  Avatar,
  Button,
  Input,
  Spinner,
  EmptyBoxIcon,
  CatFaceIcon,
  MessageBubbleIcon,
  Badge,
  Modal,
  Divider,
  EmptyState,
  useTimeAgo,
  PostSkeleton,
  ProfileHoverCard,
  ImageUploadField,
  normalizeIsoDate,
  isEditedTimestamp,
});
