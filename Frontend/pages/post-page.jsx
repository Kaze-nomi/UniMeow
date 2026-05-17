

function PostPage({ postId, currentUser, onNavigate }) {
  const [post, setPost] = React.useState(null);
  const [author, setAuthor] = React.useState(null);
  const [comments, setComments] = React.useState([]);
  const [loading, setLoading] = React.useState(true);
  const [liked, setLiked] = React.useState(false);
  const [likes, setLikes] = React.useState(0);
  const [liking, setLiking] = React.useState(false);
  const [likePulse, setLikePulse] = React.useState(false);
  const [commentText, setCommentText] = React.useState('');
  const [replyTo, setReplyTo] = React.useState(null);
  const [replyMention, setReplyMention] = React.useState('');
  const [commenting, setCommenting] = React.useState(false);
  const [editModal, setEditModal] = React.useState(false);
  const [editContent, setEditContent] = React.useState('');
  const [editLoading, setEditLoading] = React.useState(false);
  const [deleteModal, setDeleteModal] = React.useState(false);
  const [deleteLoading, setDeleteLoading] = React.useState(false);
  const [copied, setCopied] = React.useState(false);
  const [lightboxIdx, setLightboxIdx] = React.useState(null);

  React.useEffect(() => {
    setLoading(true);
    Promise.all([
      API.gql(API.Q.getPost, { id: postId }),
      API.gql(API.Q.getComments, { postId, size: 50 }),
    ]).then(([pd, cd]) => {
      const p = pd.getPost;
      setPost(p); setLiked(p.likedByMe); setLikes(p.likesCount);
      setComments(cd.getComments.comments);
      return API.getCachedUser(p.authorId);
    }).then(setAuthor).catch(() => {}).finally(() => setLoading(false));
  }, [postId]);

  const refreshComments = React.useCallback(async () => {
    const d = await API.gql(API.Q.getComments, { postId, size: 50 });
    setComments(d.getComments.comments || []);
  }, [postId]);

  const handleCommentLikeChange = React.useCallback((commentId, likedByMe, likesCount) => {
    setComments(cs => cs.map(c => c.id === commentId ? { ...c, likedByMe, likesCount } : c));
    refreshComments().catch(() => {});
  }, [refreshComments]);

  const handleLike = async () => {
    if (!currentUser) { onNavigate('/login'); return; }
    if (liking) return;
    setLiking(true);
    const wasLiked = liked;
    const previousLikes = likes || 0;
    setLiked(!wasLiked);
    setLikes(Math.max(0, previousLikes + (wasLiked ? -1 : 1)));
    try {
      if (wasLiked) {
        await API.gql(API.M.unlikePost, { postId });
      } else {
        await API.gql(API.M.likePost, { postId });
        setLikePulse(false);
        requestAnimationFrame(() => setLikePulse(true));
        setTimeout(() => setLikePulse(false), 380);
      }
      try {
        const fresh = await API.gql(API.Q.getPost, { id: postId });
        if (fresh?.getPost) {
          setPost(fresh.getPost);
          setLiked(!!fresh.getPost.likedByMe);
          setLikes(fresh.getPost.likesCount ?? 0);
        }
      } catch {}
    } catch (e) {
      setLiked(wasLiked);
      setLikes(previousLikes);
      if (e.isUnauth) onNavigate('/login');
    } finally { setLiking(false); }
  };

  const replyPrefix = (username) => username ? `@${username} ` : '';
  const escapeRegExp = (value) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const removeReplyMention = (value, username) => {
    if (!username) return value;
    return value.replace(new RegExp(`^@${escapeRegExp(username)}\\s*`), '');
  };
  const hasReplyMention = (value, username) => {
    if (!username) return false;
    return new RegExp(`^@${escapeRegExp(username)}(?:\\s|$)`).test(value);
  };
  const removeLeadingMention = (value) => value.replace(/^@\S+\s*/, '');

  const handleReply = (parentComment, author) => {
    const nextMention = author?.username || '';
    const nextPrefix = replyPrefix(nextMention);

    setReplyTo(parentComment);
    setReplyMention(nextMention);
    setCommentText(value => {
      if (!nextPrefix) return value;
      if (!value.trim()) return nextPrefix;
      if (hasReplyMention(value, replyMention)) {
        return nextPrefix + removeReplyMention(value, replyMention);
      }
      if (/^@\S+\s*/.test(value)) {
        return nextPrefix + removeLeadingMention(value);
      }
      return nextPrefix + value;
    });
  };

  const clearReply = () => {
    setReplyTo(null);
    setReplyMention('');
    setCommentText(value => removeReplyMention(value, replyMention));
  };

  const handleComment = async () => {
    if (!commentText.trim() || !currentUser) return;
    setCommenting(true);
    try {
      const d = await API.gql(API.M.addComment, { postId, content: commentText.trim(), parentCommentId: replyTo?.id || null, clientRequestId: API.newClientRequestId() });
      const newComment = { ...d.addComment, likedByMe: false, likesCount: 0 };
      setComments(c => [newComment, ...c]);
      setPost(p => p ? { ...p, commentsCount: (p.commentsCount || 0) + 1 } : p);
      setCommentText('');
      setReplyTo(null);
      setReplyMention('');
    } catch (e) { if (e.isUnauth) onNavigate('/login'); } finally { setCommenting(false); }
  };

  const handleDelete = async () => {
    setDeleteLoading(true);
    try {
      await API.gql(currentUser?.isAdmin && !isMyPost ? API.M.adminDeletePost : API.M.deletePost, { postId });
      setDeleteModal(false);
      onNavigate('/feed');
    } finally {
      setDeleteLoading(false);
    }
  };

  const handleEdit = async () => {
    setEditLoading(true);
    try {
      const d = await API.gql(API.M.editPost, { postId, input: { content: editContent } });
      setPost(p => ({ ...p, content: d.editPost.content, updatedAt: d.editPost.updatedAt || p?.updatedAt }));
      setEditModal(false);
    } catch {} finally { setEditLoading(false); }
  };

  const handleShare = async () => {
    const url = window.location.origin + '/post/' + postId;
    try { await navigator.clipboard.writeText(url); } catch { return; }
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const timeAgo = useTimeAgo(post?.createdAt);
  const postEdited = isEditedTimestamp(post?.createdAt, post?.updatedAt);
  const displayName = author ? ((author.name && author.surname) ? `${author.name} ${author.surname}` : author.username) : '...';
  const isMyPost = currentUser && post && currentUser.id === post.authorId;
  const canDeletePost = isMyPost || currentUser?.isAdmin;
  const topComments = comments.filter(c => !c.parentCommentId);
  const repliesByParent = comments.reduce((acc, c) => {
    if (c.parentCommentId) (acc[c.parentCommentId] ||= []).push(c);
    return acc;
  }, {});
  Object.values(repliesByParent).forEach(replies => {
    replies.sort((a, b) => {
      const byCreatedAt = new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime();
      if (byCreatedAt !== 0) return byCreatedAt;
      return String(a.id).localeCompare(String(b.id));
    });
  });

  if (loading) return <div style={{ display: 'flex', justifyContent: 'center', padding: 64 }}><Spinner size={36} /></div>;
  if (!post) return <EmptyState icon={<SearchIcon size={40} color="var(--text-muted)" />} title="Запись не найдена" />;

  return (
    <div>
      <div style={{ position: 'sticky', top: 0, zIndex: 10, background: 'var(--header-bg)', backdropFilter: 'saturate(180%) blur(12px)', WebkitBackdropFilter: 'saturate(180%) blur(12px)', borderBottom: '1px solid var(--border)', padding: '12px 16px' }} data-um-header>
        <button onClick={() => onNavigate(-1)} style={{ display: 'flex', alignItems: 'center', gap: 8, background: 'none', border: 'none', color: 'var(--text)', cursor: 'pointer', fontFamily: 'inherit', fontSize: 19, fontWeight: 800 }}>
          <BackArrowIcon size={20} color="var(--text)" /> Запись
        </button>
      </div>


      <div style={{ background: 'var(--bg)', borderBottom: '1px solid var(--border)', padding: '16px 16px 14px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 14 }}>
          <div onClick={() => author && onNavigate(API.profileUrl(author))} style={{ cursor: 'pointer' }}>
            <Avatar user={author} size={42} />
          </div>
          <div style={{ flex: 1 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              <span onClick={() => author && onNavigate(API.profileUrl(author))} style={{ fontWeight: 700, fontSize: 15, cursor: 'pointer' }}>{displayName}</span>
              {author?.isStudentVerified && <CheckIcon size={13} color="var(--accent)" />}
            </div>
            <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>
              @{author?.username} · {timeAgo}
              {postEdited && ' · изменено'}
              {post.universityId && author?.university?.name && String(author.university.id) === String(post.universityId) && ` · ${author.university.name}`}
            </div>
          </div>
          {canDeletePost && (
            <div style={{ display: 'flex', gap: 4 }}>
              {isMyPost && <button onClick={() => { setEditContent(post.content); setEditModal(true); }} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-muted)', padding: 6, borderRadius: 6 }}><EditIcon size={16} /></button>}
              <button onClick={() => setDeleteModal(true)} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'oklch(0.6 0.18 15)', padding: 6, borderRadius: 6 }}><TrashIcon size={16} /></button>
            </div>
          )}
        </div>

        <p style={{ margin: '0 0 16px', fontSize: 22, lineHeight: 1.45, whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{post.content}</p>

        {post.mediaUrls?.length > 0 && (
          (() => {
            const urls = post.mediaUrls.map(url => API.resolveAssetUrl(url));
            const imageUrls = urls.filter(url => !isVideoMediaUrl(url));
            const count = urls.length;
            const odd = count % 2 === 1 && count > 1;
            const columns = count === 1 ? '1fr' : 'repeat(2,1fr)';
            return (
              <div style={{ display: 'grid', gridTemplateColumns: columns, gap: 2, borderRadius: 14, overflow: 'hidden', marginBottom: 16, border: '1px solid var(--border)' }}>
                {urls.map((url, i) => {
                  const isFirst = i === 0;
                  const isVideo = isVideoMediaUrl(url);
                  const style = { width: '100%', objectFit: 'cover', display: 'block', cursor: isVideo ? 'default' : 'zoom-in' };
                  if (count === 1) style.aspectRatio = '16/9';
                  else if (odd && isFirst) { style.gridColumn = '1 / -1'; style.aspectRatio = '16/9'; }
                  else style.aspectRatio = '1';
                  if (isVideo) return <video key={i} src={url} controls preload="metadata" style={style} />;
                  const imageIndex = imageUrls.indexOf(url);
                  return <img key={i} src={url} alt="" onClick={() => setLightboxIdx(imageIndex)} style={style} />;
                })}
              </div>
            );
          })()
        )}

        <div className="um-action-bar" style={{ display: 'flex', justifyContent: 'space-between', maxWidth: 420, marginTop: 8, marginLeft: -8, paddingTop: 8 }}>
          <ActionBtn className="um-reply-btn"
            icon={<CommentIcon size={18} color="var(--text-muted)" />}
            label={comments.length} />
          <ActionBtn onClick={handleLike} active={liked}
            className={`um-like-btn ${likePulse ? 'um-likebtn-active' : ''}`}
            icon={<HeartIcon size={18} color={liked ? 'var(--like)' : 'var(--text-muted)'} filled={liked} />}
            label={likes} activeColor="var(--like)" />
          <ActionBtn className="um-icon-btn" onClick={handleShare}
            icon={<ShareIcon size={18} color="var(--text-muted)" />}
            label="" />
        </div>
      </div>


      {currentUser ? (
        <div style={{ background: 'var(--bg)', borderBottom: '1px solid var(--border)', padding: 16 }}>
          <div style={{ display: 'flex', gap: 10, alignItems: 'flex-start' }}>
            <Avatar user={currentUser} size={36} />
            <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 8 }}>
              {replyTo && (
                <div style={{ display: 'flex', justifyContent: 'space-between', gap: 10, alignItems: 'center', color: 'var(--text-muted)', fontSize: 13 }}>
                  <span>Ответ на комментарий</span>
                  <button onClick={clearReply} style={{ background: 'none', border: 'none', color: 'var(--accent)', cursor: 'pointer', fontFamily: 'inherit', fontSize: 13 }}>Сбросить</button>
                </div>
              )}
                  <textarea value={commentText} onChange={e => { setCommentText(e.target.value.slice(0, 500)); const t = e.target; t.style.height = 'auto'; t.style.height = Math.max(72, t.scrollHeight) + 'px'; }}
                    placeholder={replyTo ? 'Написать ответ...' : 'Написать комментарий...'}
                    maxLength={500}
                    style={{ width: '100%', minHeight: 72, padding: '8px 12px', borderRadius: 16, border: '1.5px solid var(--border)', background: 'var(--bg)', color: 'var(--text)', fontFamily: 'inherit', fontSize: 14, resize: 'none', outline: 'none', boxSizing: 'border-box', overflow: 'hidden' }}
                  />
              <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                      <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>{commentText.length}/500</div>
                      <Button size="sm" onClick={handleComment} loading={commenting} disabled={!commentText.trim() || commentText.length > 500}>Отправить</Button>
                    </div>
              </div>
            </div>
          </div>
        </div>
      ) : (
        <div style={{ textAlign: 'center', padding: '16px', background: 'var(--bg)', borderBottom: '1px solid var(--border)' }}>
            <span style={{ color: 'var(--text-muted)', fontSize: 14 }}>
              <span onClick={() => onNavigate('/login')} style={{ color: 'var(--accent)', cursor: 'pointer', fontWeight: 600 }}>Войдите</span>, чтобы комментировать
            </span>
          </div>
      )}


      <div>
        {comments.length === 0 ? <EmptyState icon={<MessageBubbleIcon />} title="Пока нет комментариев" /> : null}
        {topComments.map(c => (
          <React.Fragment key={c.id}>
            <CommentItem comment={c} currentUser={currentUser} onNavigate={onNavigate}
              onReply={(author) => handleReply(c, author)}
              onDelete={(id) => setComments(cs => cs.filter(x => x.id !== id && x.parentCommentId !== id))}
              onLikeChange={handleCommentLikeChange}
              onUpdate={(id, content, updatedAt) => setComments(cs => cs.map(x => x.id === id ? { ...x, content, updatedAt: updatedAt || x.updatedAt } : x))} />
            {(repliesByParent[c.id] || []).map(reply => (
              <CommentItem key={reply.id} comment={reply} currentUser={currentUser} onNavigate={onNavigate} nested
                onReply={(author) => handleReply(c, author)}
                onDelete={(id) => setComments(cs => cs.filter(x => x.id !== id))}
                onLikeChange={handleCommentLikeChange}
                onUpdate={(id, content, updatedAt) => setComments(cs => cs.map(x => x.id === id ? { ...x, content, updatedAt: updatedAt || x.updatedAt } : x))} />
            ))}
          </React.Fragment>
        ))}
      </div>


      <Modal open={editModal} onClose={() => setEditModal(false)} title="Редактировать запись">
        <Input multiline value={editContent} onChange={e => setEditContent(e.target.value)} rows={6} />
        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 16 }}>
          <Button variant="secondary" onClick={() => setEditModal(false)}>Отмена</Button>
          <Button onClick={handleEdit} loading={editLoading}>Сохранить</Button>
        </div>
      </Modal>
      <Modal open={deleteModal} onClose={() => !deleteLoading && setDeleteModal(false)} title={currentUser?.isAdmin && !isMyPost ? 'Удалить чужую запись' : 'Удалить запись'}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <div style={{ color: 'var(--text-muted)', fontSize: 14, lineHeight: 1.5 }}>
            {currentUser?.isAdmin && !isMyPost
              ? 'Запись будет удалена администратором. Это действие нельзя отменить.'
              : 'Запись будет удалена без возможности восстановления.'}
          </div>
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
            <Button variant="secondary" onClick={() => setDeleteModal(false)} disabled={deleteLoading}>Отмена</Button>
            <Button variant="danger" onClick={handleDelete} loading={deleteLoading}>Удалить</Button>
          </div>
        </div>
      </Modal>
      {copied && ReactDOM.createPortal(
        <div style={{ position: 'fixed', bottom: 88, left: '50%', transform: 'translateX(-50%)', background: 'var(--text)', color: 'var(--bg)', padding: '8px 20px', borderRadius: 9999, fontSize: 13, fontWeight: 600, zIndex: 2000, animation: 'fadein-toast 0.15s ease', boxShadow: '0 4px 16px rgba(0,0,0,0.22)', pointerEvents: 'none', whiteSpace: 'nowrap' }}>Ссылка скопирована</div>,
        document.body
      )}
      {lightboxIdx !== null && post.mediaUrls?.length > 0 && (
        (window.ImageLightbox ? React.createElement(window.ImageLightbox, { urls: post.mediaUrls.map(url => API.resolveAssetUrl(url)).filter(url => !isVideoMediaUrl(url)), startIndex: lightboxIdx, onClose: () => setLightboxIdx(null) }) : null)
      )}
    </div>
  );
}

function CommentItem({ comment, currentUser, onNavigate, onDelete, onUpdate, onLikeChange, onReply, nested = false }) {
  const [author, setAuthor] = React.useState(null);
  const [liked, setLiked] = React.useState(comment.likedByMe);
  const [likes, setLikes] = React.useState(comment.likesCount);
  const [editing, setEditing] = React.useState(false);
  const [editText, setEditText] = React.useState(comment.content);
  const [loading, setLoading] = React.useState(false);
  const [liking, setLiking] = React.useState(false);
  const [likePulse, setLikePulse] = React.useState(false);
  const [deleteModal, setDeleteModal] = React.useState(false);
  const [deleteLoading, setDeleteLoading] = React.useState(false);
  const timeAgo = useTimeAgo(comment.createdAt);
  const edited = isEditedTimestamp(comment.createdAt, comment.updatedAt);
  const isMe = currentUser?.id === comment.authorId;

  React.useEffect(() => { API.getCachedUser(comment.authorId).then(setAuthor); }, [comment.authorId]);
  React.useEffect(() => {
    if (typeof comment.likedByMe === 'boolean') setLiked(comment.likedByMe);
    if (comment.likesCount !== undefined && comment.likesCount !== null) setLikes(comment.likesCount);
  }, [comment.id, comment.likedByMe, comment.likesCount]);
  React.useEffect(() => { setEditText(comment.content); }, [comment.id, comment.content]);

  const handleLike = async () => {
    if (!currentUser) { onNavigate('/login'); return; }
    if (liking) return;
    setLiking(true);
    const wasLiked = liked;
    const previousLikes = Number(likes || 0);
    const nextLiked = !wasLiked;
    const nextLikes = Math.max(0, previousLikes + (wasLiked ? -1 : 1));
    setLiked(nextLiked);
    setLikes(nextLikes);
    try {
      if (wasLiked) {
        await API.gql(API.M.unlikeComment, { commentId: comment.id });
      } else {
        await API.gql(API.M.likeComment, { commentId: comment.id });
        setLikePulse(false);
        requestAnimationFrame(() => setLikePulse(true));
        setTimeout(() => setLikePulse(false), 380);
      }
      onLikeChange && onLikeChange(comment.id, nextLiked, nextLikes);
    } catch (e) {
      setLiked(wasLiked);
      setLikes(previousLikes);
      if (e.isUnauth) onNavigate('/login');
    } finally { setLiking(false); }
  };

  const handleDelete = async () => {
    setDeleteLoading(true);
    try {
      await API.gql(currentUser?.isAdmin && !isMe ? API.M.adminDeleteComment : API.M.deleteComment, { commentId: comment.id });
      setDeleteModal(false);
      onDelete(comment.id);
    } finally {
      setDeleteLoading(false);
    }
  };

  const handleEdit = async () => {
    setLoading(true);
    try {
      const d = await API.gql(API.M.editComment, { commentId: comment.id, content: editText });
      onUpdate(comment.id, editText, d.editComment?.updatedAt);
      setEditing(false);
    }
    catch {} finally { setLoading(false); }
  };

  return (
    <div className="um-feed-row" style={{ background: 'var(--bg)', borderBottom: '1px solid var(--border)', padding: nested ? '12px 16px 12px 58px' : '14px 16px' }}>
      <div style={{ display: 'flex', gap: 10, alignItems: 'flex-start' }}>
        <div onClick={() => author && onNavigate(API.profileUrl(author))} style={{ cursor: 'pointer' }}>
          <Avatar user={author} size={34} />
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 4, flexWrap: 'wrap' }}>
            <span onClick={() => author && onNavigate(API.profileUrl(author))} style={{ fontWeight: 700, fontSize: 13, cursor: 'pointer' }}>
              {author ? (author.name && author.surname ? `${author.name} ${author.surname}` : author.name || author.username) : '...'}
            </span>
            {author?.username && <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>@{author.username}</span>}
            <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>{timeAgo}</span>
            {edited && <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>· изменено</span>}
            {(isMe || currentUser?.isAdmin) && (
              <div style={{ marginLeft: 'auto', display: 'flex', gap: 2 }}>
                {isMe && <button onClick={() => setEditing(e => !e)} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-muted)', padding: 3 }}><EditIcon size={13} /></button>}
                <button onClick={() => setDeleteModal(true)} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'oklch(0.6 0.18 15)', padding: 3 }}><TrashIcon size={13} /></button>
              </div>
            )}
          </div>
          {editing ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
              <textarea value={editText} onChange={e => setEditText(e.target.value.slice(0, 500))}
                maxLength={500}
                style={{ width: '100%', padding: '6px 10px', borderRadius: 8, border: '1.5px solid var(--border)', background: 'var(--bg)', color: 'var(--text)', fontFamily: 'inherit', fontSize: 13, resize: 'vertical', outline: 'none', boxSizing: 'border-box', wordBreak: 'break-word', overflowWrap: 'anywhere' }} rows={3} />
              <div style={{ display: 'flex', gap: 6 }}>
                <Button size="sm" onClick={handleEdit} loading={loading}>Сохранить</Button>
                <Button size="sm" variant="secondary" onClick={() => setEditing(false)}>Отмена</Button>
              </div>
            </div>
          ) : (
            <p style={{ margin: '0 0 6px', fontSize: 14, lineHeight: 1.6, whiteSpace: 'pre-wrap', wordBreak: 'break-word', overflowWrap: 'break-word' }}>{comment.content}</p>
          )}
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginTop: 2, marginLeft: -8 }}>
            <ActionBtn onClick={handleLike} active={liked}
              className={`um-like-btn ${likePulse ? 'um-likebtn-active' : ''}`}
              icon={<HeartIcon size={14} color={liked ? 'oklch(0.6 0.22 15)' : 'var(--text-muted)'} filled={liked} />}
              label={likes} activeColor="oklch(0.6 0.22 15)" />
            <ActionBtn onClick={() => onReply && onReply(author)}
              icon={<CommentIcon size={14} color="var(--text-muted)" />}
              label="Ответить" />
          </div>
          <Modal open={deleteModal} onClose={() => !deleteLoading && setDeleteModal(false)} title={currentUser?.isAdmin && !isMe ? 'Удалить чужой комментарий' : 'Удалить комментарий'}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
              <div style={{ color: 'var(--text-muted)', fontSize: 14, lineHeight: 1.5 }}>
                {currentUser?.isAdmin && !isMe
                  ? 'Комментарий будет удалён администратором. Это действие нельзя отменить.'
                  : 'Комментарий будет удалён без возможности восстановления.'}
              </div>
              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
                <Button size="sm" variant="secondary" onClick={() => setDeleteModal(false)} disabled={deleteLoading}>Отмена</Button>
                <Button size="sm" variant="danger" onClick={handleDelete} loading={deleteLoading}>Удалить</Button>
              </div>
            </div>
          </Modal>
        </div>
      </div>
    </div>
  );
}

Object.assign(window, { PostPage, CommentItem });
