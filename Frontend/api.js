
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

  const NON_IDEMPOTENT_OPS = [
    'createPost', 'addComment',
    'createImprovementSuggestion',
    'createUniversityProposal', 'createFacultyProposal', 'createProgramProposal',
    'createProgram',
    'verifyEmailCode', 'sendVerificationCode',
  ];
  function isNonIdempotent(query) {
    return NON_IDEMPOTENT_OPS.some(op => query.includes(op + '('));
  }
  function isTransientGqlError(err) {
    if (!err?.gqlErrors) return false;
    return err.gqlErrors.some(e => {
      const code = e.extensions?.code;
      const grpc = e.extensions?.grpcStatus;
      if (code === 'UPSTREAM_ERROR' || code === 'SERVICE_UNAVAILABLE' || code === 'GATEWAY_TIMEOUT') return true;
      if (grpc === 'INTERNAL' || grpc === 'UNAVAILABLE' || grpc === 'UNKNOWN') return true;
      if (typeof e.message === 'string' && /end-of-stream|stream closed|connection closed|rst_stream/i.test(e.message)) return true;
      return false;
    });
  }

  async function gql(query, variables = {}, _authRetry = true, _transportRetries = 1) {
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
      const aborted = networkErr.name === 'AbortError';
      if (_transportRetries > 0 && (aborted || !isNonIdempotent(query))) {
        return gql(query, variables, _authRetry, _transportRetries - 1);
      }
      throw networkErr;
    }

    if (resp.status === 401) {
      if (_authRetry) {
        const ok = await tryRefresh();
        if (ok) return gql(query, variables, false, _transportRetries);
      }
      window.__authFailed && window.__authFailed();
      const err = new Error('UNAUTHORIZED'); err.isUnauth = true; throw err;
    }

    const data = await resp.json();

    if (data.errors) {
      const isUnauth = data.errors.some(e =>
        e.extensions?.classification === 'UNAUTHORIZED' ||
        e.extensions?.code === 'UNAUTHORIZED' ||
        /unauth/i.test(e.message)
      );
      if (isUnauth && _authRetry) {
        const ok = await tryRefresh();
        if (ok) return gql(query, variables, false, _transportRetries);
        window.__authFailed && window.__authFailed();
        const err = new Error('UNAUTHORIZED'); err.isUnauth = true; throw err;
      }
      const err = new Error(data.errors.map(e => e.message).join(', '));
      err.gqlErrors = data.errors;
      if (_transportRetries > 0 && !isNonIdempotent(query) && isTransientGqlError(err)) {
        return gql(query, variables, _authRetry, _transportRetries - 1);
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

  const Q = {
    me: `query { me { id username name surname avatarUrl coverUrl bio status emailGoogle emailUniversity university { id name shortName subdomain iconUrl } faculty { id name shortName } program { id facultyId name shortName } course educationLevel graduationYear isStudentVerified isEmployeeVerified isAdmin isBanned bannedUntil banReason createdAt } }`,
    getUser: `query GetUser($id:ID!) { getUser(id:$id) { id username name surname avatarUrl coverUrl bio status isStudentVerified isEmployeeVerified isAdmin isBanned bannedUntil banReason isFollowedByMe university { id name shortName subdomain iconUrl } faculty { id name shortName } program { id facultyId name shortName } course educationLevel graduationYear createdAt } }`,
    getUserByUsername: `query($username:String!) { getUserByUsername(username:$username) { id username name surname avatarUrl coverUrl bio status isStudentVerified isEmployeeVerified isAdmin isBanned bannedUntil banReason isFollowedByMe university { id name shortName subdomain iconUrl } faculty { id name shortName } program { id facultyId name shortName } course educationLevel graduationYear createdAt } }`,
    trendingFeed: `query($cursor:String,$size:Int,$universityId:ID,$facultyId:ID,$programId:ID,$topicId:ID) { trendingFeed(cursor:$cursor,size:$size,universityId:$universityId,facultyId:$facultyId,programId:$programId,topicId:$topicId) { posts { id authorId author { id username name surname avatarUrl coverUrl bio status isStudentVerified isEmployeeVerified university { id name shortName subdomain iconUrl } faculty { id name shortName } program { id facultyId name shortName } } content mediaUrls likesCount commentsCount likedByMe createdAt universityId facultyId programId } nextCursor hasMore } }`,
    followingFeed: `query($cursor:String,$size:Int,$universityId:ID,$facultyId:ID,$programId:ID,$topicId:ID) { followingFeed(cursor:$cursor,size:$size,universityId:$universityId,facultyId:$facultyId,programId:$programId,topicId:$topicId) { posts { id authorId author { id username name surname avatarUrl coverUrl bio status isStudentVerified isEmployeeVerified university { id name shortName subdomain iconUrl } faculty { id name shortName } program { id facultyId name shortName } } content mediaUrls likesCount commentsCount likedByMe createdAt universityId facultyId programId } nextCursor hasMore } }`,
    getPost: `query($id:ID!) { getPost(id:$id) { id authorId author { id username name surname avatarUrl coverUrl bio status isStudentVerified isEmployeeVerified university { id name shortName subdomain iconUrl } faculty { id name shortName } program { id facultyId name shortName } } content mediaUrls likesCount commentsCount likedByMe createdAt updatedAt universityId facultyId programId } }`,
    getUserPosts: `query($userId:ID!,$page:Int,$size:Int) { getUserPosts(userId:$userId,page:$page,size:$size) { posts { id authorId author { id username name surname avatarUrl coverUrl bio status isStudentVerified isEmployeeVerified university { id name shortName subdomain iconUrl } faculty { id name shortName } program { id facultyId name shortName } } content mediaUrls likesCount commentsCount likedByMe createdAt } total } }`,
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
    createPost: `mutation($input:CreatePostInput!) { createPost(input:$input) { id content authorId mediaUrls createdAt } }`,
    editPost: `mutation($postId:ID!,$input:EditPostInput!) { editPost(postId:$postId,input:$input) { id content mediaUrls updatedAt } }`,
    deletePost: `mutation($postId:ID!) { deletePost(postId:$postId) { success } }`,
    likePost: `mutation($postId:ID!) { likePost(postId:$postId) { success } }`,
    unlikePost: `mutation($postId:ID!) { unlikePost(postId:$postId) { success } }`,
    addComment: `mutation($postId:ID!,$content:String!,$parentCommentId:ID) { addComment(postId:$postId,content:$content,parentCommentId:$parentCommentId) { id content authorId parentCommentId createdAt updatedAt } }`,
    editComment: `mutation($commentId:ID!,$content:String!) { editComment(commentId:$commentId,content:$content) { id content updatedAt } }`,
    deleteComment: `mutation($commentId:ID!) { deleteComment(commentId:$commentId) { success } }`,
    likeComment: `mutation($commentId:ID!) { likeComment(commentId:$commentId) { success } }`,
    unlikeComment: `mutation($commentId:ID!) { unlikeComment(commentId:$commentId) { success } }`,
    createProgram: `mutation($facultyId:ID!,$name:String!,$shortName:String!) { createProgram(facultyId:$facultyId,name:$name,shortName:$shortName) { id facultyId name shortName } }`,
    createImprovementSuggestion: `mutation($text:String!) { createImprovementSuggestion(text:$text) { success } }`,
    createUniversityProposal: `mutation($input:UniversityProposalInput!) { createUniversityProposal(input:$input) { success } }`,
    createFacultyProposal: `mutation($input:FacultyProposalInput!) { createFacultyProposal(input:$input) { success } }`,
    createProgramProposal: `mutation($input:ProgramProposalInput!) { createProgramProposal(input:$input) { success } }`,
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
    if (!resp.ok) throw new Error(data.error || 'Upload failed');
    return data.url;
  }

  function resolveAssetUrl(url) {
    if (!url) return url;
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

  window.API = {
    gql, Q, M, getCachedUser, invalidateUser, uploadFile, prepareUploadFile, resolveAssetUrl,
    universitySlug, profileUrl,
    logout: () => fetch(BASE + '/api/auth/logout', { method: 'POST', credentials: 'include' }),
    loginWithGoogle: () => { window.location.href = BASE + '/oauth2/authorization/google'; },
    baseUrl: BASE,
  };
})();
