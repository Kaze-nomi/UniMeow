
(function () {
  const BASE = (typeof __API_BASE__ !== 'undefined' && __API_BASE__) || 'http://localhost:8081';
  const GQL = BASE + '/graphql';

  let _refreshing = null;

  async function tryRefresh() {
    if (_refreshing) return _refreshing;
    _refreshing = Promise.race([
      fetch(BASE + '/api/auth/refresh', { method: 'POST', credentials: 'include' }).then(r => r.ok).catch(() => false),
      new Promise(resolve => setTimeout(() => resolve(false), 5000)),
    ]).finally(() => { _refreshing = null; });
    return _refreshing;
  }

  const TRANSPORT_ERROR_RE = /end-of-stream|stream closed|connection closed|rst_stream|http2 exception|mid-frame|goaway|deadline.exceeded|upstream/i;

  function userMessage(message) {
    const text = String(message || '').trim();
    if (!text) return 'Не удалось выполнить запрос';
    if (/[А-Яа-яЁё]/.test(text) && !/(exception|failed|unauthorized|unauthenticated|deadline|timeout|internal|upstream|network)/i.test(text)) {
      return text;
    }
    if (/unauthorized|unauthenticated|authentication required|UNAUTHORIZED/i.test(text)) {
      return 'Нужно войти в аккаунт';
    }
    if (/abort|timeout|timed out|deadline|deadline_exceeded/i.test(text)) {
      return 'Запрос занял слишком много времени. Попробуйте ещё раз.';
    }
    if (/end-of-stream|stream closed|connection closed|rst_stream|http2 exception|mid-frame|goaway|failed to fetch|network|load failed/i.test(text)) {
      return 'Временная ошибка соединения. Попробуйте ещё раз.';
    }
    if (/user not found/i.test(text)) return 'Пользователь не найден';
    if (/post not found/i.test(text)) return 'Запись не найдена';
    if (/comment not found/i.test(text)) return 'Комментарий не найден';
    if (/username.*(taken|already|exists)|already.*username/i.test(text)) return 'Этот никнейм уже занят';
    if (/content.*(blank|empty|required)|must not be blank|must not be empty/i.test(text)) return 'Добавьте текст записи';
    if (/too large|payload|max.*size/i.test(text)) return 'Файл слишком большой';
    if (/upload failed/i.test(text)) return 'Не удалось загрузить файл';
    if (/banned|blocked|permanent ban/i.test(text)) return 'Аккаунт заблокирован';
    if (/forbidden|access denied|permission/i.test(text)) return 'Недостаточно прав';
    if (/upstream|internal|unknown|service unavailable|gateway/i.test(text)) {
      return 'Временная ошибка сервиса. Попробуйте ещё раз.';
    }
    return text;
  }

  function isTransientGqlError(err) {
    if (!err?.gqlErrors) return false;
    return err.gqlErrors.some(e => {
      const code = e.extensions?.code;
      const grpc = e.extensions?.grpcStatus;
      if (code === 'UPSTREAM_ERROR' || code === 'SERVICE_UNAVAILABLE' || code === 'GATEWAY_TIMEOUT') return true;
      if (grpc === 'INTERNAL' || grpc === 'UNAVAILABLE' || grpc === 'UNKNOWN' || grpc === 'DEADLINE_EXCEEDED') return true;
      if (typeof e.message === 'string' && TRANSPORT_ERROR_RE.test(e.message)) return true;
      return false;
    });
  }

  async function retryGraphQl(query, variables, authRetry, retriesLeft) {
    await new Promise(r => setTimeout(r, 200));
    return gql(query, variables, authRetry, retriesLeft - 1);
  }

  async function gql(query, variables = {}, _authRetry = true, _transportRetries = 3) {
    if (window.MOCK?.enabled) {
      const mocked = await window.MOCK.gql(query, variables);
      if (mocked !== undefined) return mocked;
    }

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 30000);
    let resp;
    let networkErr = null;
    try {
      resp = await fetch(GQL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ query, variables }),
        signal: controller.signal,
      });
    } catch (e) {
      networkErr = e;
    } finally {
      clearTimeout(timeout);
    }

    if (networkErr) {
      if (_transportRetries > 0) {
        return retryGraphQl(query, variables, _authRetry, _transportRetries);
      }
      throw new Error(userMessage(networkErr.message));
    }

    if (resp.status === 401) {
      if (_authRetry) {
        const ok = await tryRefresh();
        if (ok) return gql(query, variables, false, _transportRetries);
      }
      window.__authFailed && window.__authFailed();
      const err = new Error(userMessage('UNAUTHORIZED')); err.isUnauth = true; throw err;
    }

    const data = await resp.json();

    if (data.errors) {
      const isBanned = data.errors.some(e => e.extensions?.code === 'BANNED' || /banned|blocked/i.test(e.message));
      const isUnauth = data.errors.some(e =>
        e.extensions?.classification === 'UNAUTHORIZED' ||
        e.extensions?.code === 'UNAUTHORIZED' ||
        /unauth/i.test(e.message)
      );
      if (isUnauth && _authRetry) {
        const ok = await tryRefresh();
        if (ok) return gql(query, variables, false, _transportRetries);
        window.__authFailed && window.__authFailed();
        const err = new Error(userMessage('UNAUTHORIZED')); err.isUnauth = true; throw err;
      }
      const err = new Error(userMessage(data.errors.map(e => e.message).join(', ')));
      err.gqlErrors = data.errors;
      if (isBanned) {
        window.dispatchEvent(new CustomEvent('um-account-banned'));
      }
      if (_transportRetries > 0 && isTransientGqlError(err)) {
        return retryGraphQl(query, variables, _authRetry, _transportRetries);
      }
      throw err;
    }

    return data.data;
  }


  const _userCache = {};
  async function getCachedUser(id) {
    if (!id) return null;
    if (_userCache[id]) return _userCache[id];
    try {
      const d = await gql(Q.getUser, { id });
      _userCache[id] = d.getUser;
      return d.getUser;
    } catch { return null; }
  }
  function invalidateUser(id) { delete _userCache[id]; }
  function clearUserCache() { Object.keys(_userCache).forEach(id => delete _userCache[id]); }

  const Q = {
    me: `query { me { id username name surname avatarUrl coverUrl bio status emailGoogle emailUniversity university { id name shortName subdomain iconUrl } faculty { id name shortName } program { id facultyId name shortName } course educationLevel graduationYear isStudentVerified isEmployeeVerified isAdmin isBanned bannedUntil banReason createdAt } }`,
    getUser: `query GetUser($id:ID!) { getUser(id:$id) { id username name surname avatarUrl coverUrl bio status isStudentVerified isEmployeeVerified isAdmin isBanned bannedUntil banReason isFollowedByMe university { id name shortName subdomain iconUrl } faculty { id name shortName } program { id facultyId name shortName } course educationLevel graduationYear createdAt } }`,
    getUserByUsername: `query($username:String!) { getUserByUsername(username:$username) { id username name surname avatarUrl coverUrl bio status isStudentVerified isEmployeeVerified isAdmin isBanned bannedUntil banReason isFollowedByMe university { id name shortName subdomain iconUrl } faculty { id name shortName } program { id facultyId name shortName } course educationLevel graduationYear createdAt } }`,
    listFollowing: `query($userId:ID!) { listFollowing(userId:$userId) { id username name surname avatarUrl bio status isStudentVerified isEmployeeVerified isAdmin isBanned university { id name shortName subdomain iconUrl } faculty { id name shortName } program { id facultyId name shortName } createdAt } }`,
    listFollowers: `query($userId:ID!) { listFollowers(userId:$userId) { id username name surname avatarUrl bio status isStudentVerified isEmployeeVerified isAdmin isBanned university { id name shortName subdomain iconUrl } faculty { id name shortName } program { id facultyId name shortName } createdAt } }`,
    trendingFeed: `query($cursor:String,$size:Int,$universityId:ID,$facultyId:ID,$programId:ID,$topicId:ID) { trendingFeed(cursor:$cursor,size:$size,universityId:$universityId,facultyId:$facultyId,programId:$programId,topicId:$topicId) { posts { id authorId author { id username name surname avatarUrl coverUrl bio status isStudentVerified isEmployeeVerified university { id name shortName subdomain iconUrl } faculty { id name shortName } program { id facultyId name shortName } } content mediaUrls likesCount commentsCount likedByMe createdAt universityId facultyId programId } nextCursor hasMore } }`,
    followingFeed: `query($cursor:String,$size:Int,$universityId:ID,$facultyId:ID,$programId:ID,$topicId:ID) { followingFeed(cursor:$cursor,size:$size,universityId:$universityId,facultyId:$facultyId,programId:$programId,topicId:$topicId) { posts { id authorId author { id username name surname avatarUrl coverUrl bio status isStudentVerified isEmployeeVerified university { id name shortName subdomain iconUrl } faculty { id name shortName } program { id facultyId name shortName } } content mediaUrls likesCount commentsCount likedByMe createdAt universityId facultyId programId } nextCursor hasMore } }`,
    getPost: `query($id:ID!) { getPost(id:$id) { id authorId author { id username name surname avatarUrl coverUrl bio status isStudentVerified isEmployeeVerified university { id name shortName subdomain iconUrl } faculty { id name shortName } program { id facultyId name shortName } } content mediaUrls likesCount commentsCount likedByMe createdAt updatedAt universityId facultyId programId } }`,
    getUserPosts: `query($userId:ID!,$page:Int,$size:Int) { getUserPosts(userId:$userId,page:$page,size:$size) { posts { id authorId author { id username name surname avatarUrl coverUrl bio status isStudentVerified isEmployeeVerified university { id name shortName subdomain iconUrl } faculty { id name shortName } program { id facultyId name shortName } } content mediaUrls likesCount commentsCount likedByMe createdAt universityId facultyId programId } total } }`,
    getComments: `query($postId:ID!,$page:Int,$size:Int) { getComments(postId:$postId,page:$page,size:$size) { comments { id postId authorId content likesCount likedByMe createdAt updatedAt parentCommentId } total } }`,
    listUniversities: `query { listUniversities { id name shortName subdomain iconUrl } }`,
    listFaculties:    `query($universityId:ID!) { listFaculties(universityId:$universityId) { id name shortName } }`,
    listPrograms:     `query($facultyId:ID!) { listPrograms(facultyId:$facultyId) { id facultyId name shortName } }`,
    getNotifications: `query($page:Int,$size:Int) { getNotifications(page:$page,size:$size) { notifications { id userId actorId actor { id username name surname avatarUrl } type entityId entityType parentEntityId isRead createdAt } total } }`,
    getUnreadNotificationCount: `query { getUnreadNotificationCount }`,
    adminImprovementSuggestions: `query { adminImprovementSuggestions { id authorId text status createdAt } }`,
    adminUniversityProposals: `query { adminUniversityProposals { id authorId name shortName subdomain studentDomain employeeDomain city description status createdAt reviewedBy reviewedAt iconUrl } }`,
    adminFacultyProposals: `query { adminFacultyProposals { id authorId universityId universityName universityShortName name shortName status createdAt reviewedBy reviewedAt } }`,
    adminProgramProposals: `query { adminProgramProposals { id authorId universityId universityName universityShortName facultyId facultyName facultyShortName name shortName status createdAt reviewedBy reviewedAt } }`,
  };

  const M = {
    updateProfile: `mutation($input:UpdateProfileInput!) { updateProfile(input:$input) { id username name surname avatarUrl coverUrl bio status emailGoogle emailUniversity isStudentVerified isEmployeeVerified isAdmin isBanned bannedUntil banReason university { id name shortName subdomain iconUrl } faculty { id name shortName } program { id facultyId name shortName } course educationLevel graduationYear } }`,
    sendVerificationCode: `mutation($email:String!) { sendVerificationCode(universityEmail:$email) { success message } }`,
    verifyEmailCode: `mutation($code:String!) { verifyEmailCode(code:$code) { success error } }`,
    subscribe: `mutation($id:ID!) { subscribe(targetUserId:$id) { success } }`,
    unsubscribe: `mutation($id:ID!) { unsubscribe(targetUserId:$id) { success } }`,
    adminGrantAdmin: `mutation($targetUserId:ID!) { adminGrantAdmin(targetUserId:$targetUserId) { id username name surname avatarUrl coverUrl bio status emailGoogle emailUniversity university { id name shortName subdomain iconUrl } faculty { id name shortName } program { id facultyId name shortName } course educationLevel graduationYear isStudentVerified isEmployeeVerified isAdmin isBanned bannedUntil banReason createdAt } }`,
    deleteAccount: `mutation { deleteAccount { success } }`,
    createPost: `mutation($input:CreatePostInput!,$clientRequestId:String) { createPost(input:$input,clientRequestId:$clientRequestId) { id content authorId mediaUrls likesCount commentsCount likedByMe createdAt universityId facultyId programId } }`,
    editPost: `mutation($postId:ID!,$input:EditPostInput!) { editPost(postId:$postId,input:$input) { id content mediaUrls updatedAt } }`,
    deletePost: `mutation($postId:ID!) { deletePost(postId:$postId) { success } }`,
    likePost: `mutation($postId:ID!) { likePost(postId:$postId) { success } }`,
    unlikePost: `mutation($postId:ID!) { unlikePost(postId:$postId) { success } }`,
    addComment: `mutation($postId:ID!,$content:String!,$parentCommentId:ID,$clientRequestId:String) { addComment(postId:$postId,content:$content,parentCommentId:$parentCommentId,clientRequestId:$clientRequestId) { id content authorId parentCommentId createdAt updatedAt } }`,
    editComment: `mutation($commentId:ID!,$content:String!) { editComment(commentId:$commentId,content:$content) { id content updatedAt } }`,
    deleteComment: `mutation($commentId:ID!) { deleteComment(commentId:$commentId) { success } }`,
    likeComment: `mutation($commentId:ID!) { likeComment(commentId:$commentId) { success } }`,
    unlikeComment: `mutation($commentId:ID!) { unlikeComment(commentId:$commentId) { success } }`,
    createImprovementSuggestion: `mutation($text:String!,$clientRequestId:String) { createImprovementSuggestion(text:$text,clientRequestId:$clientRequestId) { success } }`,
    createUniversityProposal: `mutation($input:UniversityProposalInput!,$clientRequestId:String) { createUniversityProposal(input:$input,clientRequestId:$clientRequestId) { success } }`,
    createFacultyProposal: `mutation($input:FacultyProposalInput!,$clientRequestId:String) { createFacultyProposal(input:$input,clientRequestId:$clientRequestId) { success } }`,
    createProgramProposal: `mutation($input:ProgramProposalInput!,$clientRequestId:String) { createProgramProposal(input:$input,clientRequestId:$clientRequestId) { success } }`,
    adminBanUser: `mutation($input:BanUserInput!) { adminBanUser(input:$input) { success } }`,
    adminDeletePost: `mutation($postId:ID!) { adminDeletePost(postId:$postId) { success } }`,
    adminDeleteComment: `mutation($commentId:ID!) { adminDeleteComment(commentId:$commentId) { success } }`,
    adminDeleteSuggestion: `mutation($id:ID!) { adminDeleteSuggestion(id:$id) { success } }`,
    adminReviewUniversityProposal: `mutation($proposalId:ID!,$status:String!) { adminReviewUniversityProposal(proposalId:$proposalId,status:$status) { success } }`,
    adminReviewFacultyProposal: `mutation($proposalId:ID!,$status:String!) { adminReviewFacultyProposal(proposalId:$proposalId,status:$status) { success } }`,
    adminReviewProgramProposal: `mutation($proposalId:ID!,$status:String!) { adminReviewProgramProposal(proposalId:$proposalId,status:$status) { success } }`,
    markAllNotificationsRead: `mutation { markAllNotificationsRead }`,
  };

  const MAX_UPLOAD_SIZE = 10 * 1024 * 1024;

  async function compressImageFile(file) {
    if (!file?.type?.startsWith('image/')) return file;
    if (file.type === 'image/gif' || file.type === 'image/svg+xml') return file;
    if (file.size < 350 * 1024) return file;

    const bitmap = await createImageBitmap(file);
    const maxSide = 1920;
    const scale = Math.min(1, maxSide / Math.max(bitmap.width, bitmap.height));
    const width = Math.max(1, Math.round(bitmap.width * scale));
    const height = Math.max(1, Math.round(bitmap.height * scale));
    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    const ctx = canvas.getContext('2d', { alpha: false });
    ctx.drawImage(bitmap, 0, 0, width, height);
    bitmap.close?.();

    const outputType = file.type === 'image/png' && file.size < 1024 * 1024 ? 'image/png' : 'image/jpeg';
    const blob = await new Promise(resolve => canvas.toBlob(resolve, outputType, 0.86));
    if (!blob || blob.size >= file.size) return file;
    const ext = outputType === 'image/jpeg' ? '.jpg' : '.png';
    const name = file.name.replace(/\.[^.]+$/, '') + ext;
    return new File([blob], name, { type: outputType, lastModified: Date.now() });
  }

  async function prepareUploadFile(file) {
    const prepared = await compressImageFile(file);
    if (prepared.size > MAX_UPLOAD_SIZE) {
      throw new Error('Файл слишком большой (максимум 10 МБ)');
    }
    return prepared;
  }

  async function uploadFile(file, bucket = 'post-media') {
    const prepared = await prepareUploadFile(file);
    if (window.MOCK?.enabled) {
      return new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => resolve(reader.result);
        reader.onerror = () => reject(new Error('Read error'));
        reader.readAsDataURL(prepared);
      });
    }
    const form = new FormData();
    form.append('file', prepared);
    const doUpload = async () => {
      const resp = await fetch(BASE + '/api/upload?bucket=' + encodeURIComponent(bucket), {
        method: 'POST',
        credentials: 'include',
        body: form,
      });
      const text = await resp.text();
      let data = {};
      if (text) {
        try { data = JSON.parse(text); } catch { data = { error: text }; }
      }
      return { resp, data };
    };

    let { resp, data } = await doUpload();
    if (resp.status === 401) {
      const ok = await tryRefresh();
      if (ok) {
        ({ resp, data } = await doUpload());
      }
    }
    if (resp.status === 403 && /заблок|banned|blocked/i.test(data.error || '')) {
      window.dispatchEvent(new CustomEvent('um-account-banned'));
    }
    if (!resp.ok) throw new Error(userMessage(data.error || 'Upload failed'));
    return data.url;
  }

  function resolveAssetUrl(url) {
    if (!url) return url;
    const publicMinioBase = (typeof __MINIO_PUBLIC_URL__ !== 'undefined' && __MINIO_PUBLIC_URL__) || 'http://localhost:9000';
    const normalizedPublicMinioBase = publicMinioBase.endsWith('/') ? publicMinioBase.slice(0, -1) : publicMinioBase;
    if (/^https?:\/\/(localhost|127\.0\.0\.1):9000\//i.test(url)) {
      return normalizedPublicMinioBase + url.replace(/^https?:\/\/(localhost|127\.0\.0\.1):9000/i, '');
    }
    if (/^(https?:)?\/\//i.test(url) || /^(data|blob):/i.test(url)) return url;
    return url;
  }

  function universitySlug(uni) {
    if (uni?.subdomain) return uni.subdomain.toString().trim().toLowerCase();
    const key = (uni?.shortName || uni?.name || uni?.id || '').toString().trim().toLowerCase();
    const known = { 'ниу вшэ': 'hse', 'вшэ': 'hse', 'hse': 'hse', 'мгу': 'msu', 'msu': 'msu', 'итмо': 'itmo', 'itmo': 'itmo' };
    if (known[key]) return known[key];
    return key.replace(/ниу\s*/g, '').replace(/[^a-z0-9а-яё]+/g, '-').replace(/^-+|-+$/g, '') || String(uni?.id || 'feed');
  }

  function profileUrl(user) {
    if (user?.username) return '/profile/' + user.username;
    return '/profile/' + (user?.id || '');
  }

  function newClientRequestId() {
    if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID();
    return 'crid-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);
  }

  window.API = {
    gql, Q, M, getCachedUser, invalidateUser, clearUserCache, userMessage, uploadFile, prepareUploadFile, resolveAssetUrl,
    universitySlug, profileUrl, newClientRequestId,
    logout: () => {
      if (window.MOCK?.enabled) {
        window.MOCK.logout();
        return Promise.resolve({ ok: true });
      }
      return fetch(BASE + '/api/auth/logout', { method: 'POST', credentials: 'include' });
    },
    loginWithGoogle: () => { window.location.href = BASE + '/oauth2/authorization/google'; },
    baseUrl: BASE,
  };
})();
