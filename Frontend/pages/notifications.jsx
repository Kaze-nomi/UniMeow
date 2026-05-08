function NotificationsPage({ currentUser, onNavigate }) {
  const [notifications, setNotifications] = React.useState([]);
  const [total, setTotal] = React.useState(0);
  const [loading, setLoading] = React.useState(true);
  const [page, setPage] = React.useState(0);
  const PAGE_SIZE = 20;

  const load = async (p = 0) => {
    if (!currentUser) return;
    setLoading(true);
    try {
      const d = await API.gql(API.Q.getNotifications, { page: p, size: PAGE_SIZE });
      const items = d.getNotifications.notifications || [];
      setNotifications(p === 0 ? items : prev => [...prev, ...items]);
      setTotal(d.getNotifications.total || 0);
    } catch (e) {
      if (!e.isUnauth) console.error(e);
    } finally {
      setLoading(false);
    }
  };

  React.useEffect(() => {
    setPage(0);
    load(0);
    API.gql(API.M.markAllNotificationsRead).then(() => {
      window.dispatchEvent(new CustomEvent('um-notifications-marked-read'));
    }).catch(() => {});
  }, [currentUser]);

  const loadMore = () => {
    const next = page + 1;
    setPage(next);
    load(next);
  };

  if (!currentUser) {
    return (
      <div style={{ padding: 32, textAlign: 'center', color: 'var(--text-muted)' }}>
        Войдите, чтобы видеть уведомления
      </div>
    );
  }

  const typeLabel = (type) => {
    const labels = {
      LIKE_POST: 'понравился ваш пост',
      LIKE_COMMENT: 'понравился ваш комментарий',
      COMMENT_ON_POST: 'прокомментировал ваш пост',
      REPLY_TO_COMMENT: 'ответил на ваш комментарий',
      MENTION_IN_POST: 'упомянул вас в посте',
      MENTION_IN_COMMENT: 'упомянул вас в комментарии',
      FOLLOW: 'подписался на вас',
      ADMIN_GRANTED: 'выдал вам права администратора',
      BANNED: 'вас заблокировал',
    };
    return labels[type] || type;
  };

  const entityLink = (n) => {
    if (n.entityType === 'POST') return '/post/' + n.entityId;
    if (n.entityType === 'COMMENT') return n.parentEntityId ? '/post/' + n.parentEntityId : null;
    if (n.entityType === 'USER') return '/profile/' + n.entityId;
    return null;
  };

  return (
    <div>
      <div style={{
        position: 'sticky', top: 0, zIndex: 10,
        background: 'var(--header-bg)',
        backdropFilter: 'saturate(180%) blur(12px)',
        WebkitBackdropFilter: 'saturate(180%) blur(12px)',
        borderBottom: '1px solid var(--border)',
        padding: '12px 16px',
        display: 'flex', alignItems: 'center', gap: 12,
      }} data-um-header>
        <BellIcon size={22} color="var(--text)" />
        <h2 style={{ margin: 0, fontSize: 20, fontWeight: 800 }}>Уведомления</h2>
      </div>

      {loading && notifications.length === 0 && (
        <div style={{ display: 'flex', justifyContent: 'center', padding: 40 }}>
          <Spinner size={28} />
        </div>
      )}

      {!loading && notifications.length === 0 && (
        <div style={{ padding: '64px 20px', textAlign: 'center', color: 'var(--text-muted)' }}>
          <BellIcon size={40} color="var(--border)" />
          <div style={{ marginTop: 12, fontSize: 16, fontWeight: 600 }}>Уведомлений пока нет</div>
          <div style={{ marginTop: 6, fontSize: 14 }}>Они появятся, когда кто-то отреагирует на ваши посты</div>
        </div>
      )}

      <div>
        {notifications.map(n => {
          const actor = n.actor;
          const link = entityLink(n);
          return (
            <div
              key={n.id}
              onClick={link ? () => onNavigate(link) : undefined}
              style={{
                display: 'flex', alignItems: 'flex-start', gap: 12,
                padding: '14px 16px',
                borderBottom: '1px solid var(--border)',
                background: n.isRead ? 'transparent' : 'var(--accent-subtle)',
                cursor: link ? 'pointer' : 'default',
                transition: 'background 0.12s',
              }}
              onMouseEnter={e => { if (link) e.currentTarget.style.background = 'var(--surface-hover)'; }}
              onMouseLeave={e => { e.currentTarget.style.background = n.isRead ? 'transparent' : 'var(--accent-subtle)'; }}
            >
              {actor ? (
                <div onClick={e => { e.stopPropagation(); onNavigate(API.profileUrl(actor)); }} style={{ flexShrink: 0, cursor: 'pointer' }}>
                  <Avatar user={actor} size={40} />
                </div>
              ) : (
                <div style={{ width: 40, height: 40, borderRadius: '50%', background: 'var(--surface-2)', flexShrink: 0 }} />
              )}
              <div style={{ flex: 1, minWidth: 0 }}>
                <span style={{ fontWeight: 700 }}>
                  {actor ? (actor.username ? '@' + actor.username : actor.name) : 'Кто-то'}
                </span>
                {' '}
                <span style={{ color: 'var(--text-muted)' }}>{typeLabel(n.type)}</span>
                <div style={{ fontSize: 12, color: 'var(--text-muted)', marginTop: 3 }}>
                  {formatRelativeTime(n.createdAt)}
                </div>
              </div>
              {!n.isRead && (
                <div style={{
                  width: 8, height: 8, borderRadius: '50%',
                  background: 'var(--accent)', flexShrink: 0, marginTop: 6,
                }} />
              )}
            </div>
          );
        })}
      </div>

      {notifications.length < total && (
        <InfiniteSentinel onIntersect={() => { if (!loading) loadMore(); }} loading={loading} />
      )}
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

function formatRelativeTime(isoString) {
  if (!isoString) return '';
  try {
    const diff = Date.now() - new Date(normalizeIsoDate(isoString)).getTime();
    const mins = Math.floor(diff / 60000);
    if (mins < 1) return 'только что';
    if (mins < 60) return mins + ' мин. назад';
    const hours = Math.floor(mins / 60);
    if (hours < 24) return hours + ' ч. назад';
    const days = Math.floor(hours / 24);
    if (days < 7) return days + ' дн. назад';
    return new Date(isoString).toLocaleDateString('ru-RU', { day: 'numeric', month: 'short' });
  } catch { return ''; }
}

Object.assign(window, { NotificationsPage });
