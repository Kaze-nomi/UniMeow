

(function () {
  const now = Date.now();
  const iso = (minutesAgo) => new Date(now - minutesAgo * 60 * 1000).toISOString();
  const svgIcon = (label, bg, fg = '#fff') =>
    `data:image/svg+xml;utf8,${encodeURIComponent(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 96 96"><rect width="96" height="96" rx="22" fill="${bg}"/><text x="48" y="58" text-anchor="middle" font-family="Georgia,serif" font-size="28" font-weight="700" fill="${fg}">${label}</text></svg>`)}`;
  const svgBanner = (from, to, text) =>
    `data:image/svg+xml;utf8,${encodeURIComponent(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1200 675"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop stop-color="${from}"/><stop offset="1" stop-color="${to}"/></linearGradient></defs><rect width="1200" height="675" fill="url(#g)"/><circle cx="970" cy="120" r="190" fill="rgba(255,255,255,.18)"/><circle cx="170" cy="570" r="240" fill="rgba(0,0,0,.13)"/><text x="70" y="560" font-family="Verdana,sans-serif" font-size="56" font-weight="700" fill="white">${text}</text></svg>`)}`;

  const universities = [
    { id: 'uni-hse', name: 'Национальный исследовательский университет Высшая школа экономики', shortName: 'НИУ ВШЭ', subdomain: 'hse', iconUrl: svgIcon('ВШЭ', '#111827') },
    { id: 'uni-msu', name: 'Московский государственный университет имени М. В. Ломоносова', shortName: 'МГУ', subdomain: 'mgu', iconUrl: svgIcon('МГУ', '#14005c') },
    { id: 'uni-itmo', name: 'Университет ИТМО', shortName: 'ИТМО', subdomain: 'itmo', iconUrl: svgIcon('ITMO', '#005bbb') },
  ];

  const faculties = [
    { id: 'fac-hse-cs', universityId: 'uni-hse', name: 'Факультет компьютерных наук', shortName: 'ФКН' },
    { id: 'fac-hse-econ', universityId: 'uni-hse', name: 'Факультет экономических наук', shortName: 'ФЭН' },
    { id: 'fac-msu-vmk', universityId: 'uni-msu', name: 'ВМК', shortName: 'ВМК' },
    { id: 'fac-msu-mechmath', universityId: 'uni-msu', name: 'Механико-математический факультет', shortName: 'Мехмат' },
    { id: 'fac-itmo-fitip', universityId: 'uni-itmo', name: 'Факультет информационных технологий и программирования', shortName: 'ФИТиП' },
  ];

  const programs = [
    { id: 'prog-hse-cs-se', facultyId: 'fac-hse-cs', name: 'Программная инженерия', shortName: 'ПИ' },
    { id: 'prog-hse-cs-ads', facultyId: 'fac-hse-cs', name: 'Прикладной анализ данных', shortName: 'ПАД' },
    { id: 'prog-hse-econ-econ', facultyId: 'fac-hse-econ', name: 'Экономика', shortName: 'Экон' },
    { id: 'prog-msu-vmk-ami', facultyId: 'fac-msu-vmk', name: 'Прикладная математика и информатика', shortName: 'ПМИ' },
    { id: 'prog-msu-mechmath-math', facultyId: 'fac-msu-mechmath', name: 'Математика', shortName: 'Мат' },
    { id: 'prog-itmo-fitip-se', facultyId: 'fac-itmo-fitip', name: 'Software Engineering', shortName: 'SE' },
  ];

  const topicNames = [
    ['general', 'Общее'],
    ['study', 'Учёба'],
    ['teachers', 'Преподаватели'],
    ['projects', 'Проекты'],
    ['exams', 'Экзамены'],
  ];

  const topics = faculties.flatMap(faculty => {
    const parent = { id: `topic-${faculty.id}`, universityId: faculty.universityId, facultyId: faculty.id, slug: `faculty-${faculty.id}`, name: faculty.shortName, isSystem: true };
    return [parent, ...topicNames.map(([slug, name]) => ({
      id: `topic-${faculty.id}-${slug}`,
      universityId: faculty.universityId,
      facultyId: faculty.id,
      parentTopicId: parent.id,
      slug,
      name,
      isSystem: true,
    }))];
  });

  const users = [
    {
      id: 'u-admin', username: 'admin_meow', name: 'Арина', surname: 'Модератор',
      bio: 'Администрирую заявки, факультеты и порядок в ленте.',
      status: 'Смотрю, чтобы UniMeow не превратился в общий чат без правил',
      avatarUrl: null, coverUrl: svgBanner('#0f766e', '#22c55e', 'UniMeow Admin'),
      university: universities[0], faculty: faculties[0], program: programs[0], course: 4, educationLevel: 'BACHELOR',
      isStudentVerified: true, isEmployeeVerified: false, isAdmin: true, isBanned: false,
      createdAt: iso(100000),
    },
    {
      id: 'u-hse-student', username: 'katya_hse', name: 'Катя', surname: 'Соколова',
      bio: 'ФКН, люблю распределённые системы, мемы про дедлайны и нормальные README.',
      avatarUrl: null, coverUrl: svgBanner('#2563eb', '#7c3aed', 'HSE CS'),
      university: universities[0], faculty: faculties[0], program: programs[0], course: 2, educationLevel: 'BACHELOR',
      isStudentVerified: true, isEmployeeVerified: false, isAdmin: false, isBanned: false,
      createdAt: iso(84000),
    },
    {
      id: 'u-msu-student', username: 'misha_msu', name: 'Миша', surname: 'Орлов',
      bio: 'ВМК МГУ. Пишу конспекты так, чтобы через неделю самому было понятно.',
      avatarUrl: null, coverUrl: svgBanner('#7c2d12', '#f97316', 'MSU VMK'),
      university: universities[1], faculty: faculties[2], program: programs[3], course: 3, educationLevel: 'SPECIALIST',
      isStudentVerified: true, isEmployeeVerified: false, isAdmin: false, isBanned: false,
      createdAt: iso(71000),
    },
    {
      id: 'u-teacher', username: 'prof_nika', name: 'Вероника', surname: 'Лебедева',
      bio: 'Преподаватель. Отвечаю на вопросы, если вопрос сформулирован короче курсовой.',
      avatarUrl: null, coverUrl: svgBanner('#166534', '#14b8a6', 'Office hours'),
      university: universities[0], faculty: faculties[0], program: programs[1], educationLevel: null,
      isStudentVerified: false, isEmployeeVerified: true, isAdmin: false, isBanned: false,
      createdAt: iso(120000),
    },
    {
      id: 'u-guest', username: 'new_student', name: 'Лёша', surname: 'Новиков',
      bio: 'Поступаю и выбираю университет. Пока без верификации.',
      avatarUrl: null, coverUrl: svgBanner('#6b7280', '#94a3b8', 'Future student'),
      university: null, faculty: null, program: null, course: null, educationLevel: null,
      isStudentVerified: false, isEmployeeVerified: false, isAdmin: false, isBanned: false,
      createdAt: iso(9000),
    },
  ];

  const byId = (list, id) => list.find(item => item.id === id);
  const publicUser = (user) => user ? { ...user, emailGoogle: undefined, emailUniversity: undefined } : null;
  const withAuthor = (post) => ({ ...post, author: publicUser(byId(users, post.authorId)) });

  let currentUserId = localStorage.getItem('um-mock-user') || '';
  let posts = [
    {
      id: 'p-1001', authorId: 'u-hse-student', universityId: 'uni-hse', facultyId: 'fac-hse-cs', programId: 'prog-hse-cs-se', topicId: null,
      content: 'Собрала короткий чеклист по подготовке к алгоритмам: не зубрить 40 тем подряд, а каждый день решать одну задачу и обязательно писать разбор. Работает лучше, чем ночной марафон перед дедлайном.',
      mediaUrls: [svgBanner('#111827', '#2563eb', 'Algorithms checklist')],
      likesCount: 42, commentsCount: 3, likedByMe: false, createdAt: iso(15),
    },
    {
      id: 'p-1002', authorId: 'u-msu-student', universityId: 'uni-msu', facultyId: 'fac-msu-vmk', programId: 'prog-msu-vmk-ami', topicId: null,
      content: 'Кто-нибудь из ВМК уже пробовал вести общий конспект по матану в Notion или Obsidian? Хочется понять, что удобнее для совместной подготовки.',
      mediaUrls: [], likesCount: 27, commentsCount: 2, likedByMe: false, createdAt: iso(42),
    },
    {
      id: 'p-1003', authorId: 'u-teacher', universityId: 'uni-hse', facultyId: 'fac-hse-cs', programId: 'prog-hse-cs-ads', topicId: null,
      content: 'Напоминание: хороший вопрос преподавателю содержит контекст, ожидаемое поведение, фактическое поведение и минимальный пример. Так ответ получается быстрее и полезнее для всех.',
      mediaUrls: [], likesCount: 88, commentsCount: 5, likedByMe: false, createdAt: iso(90),
    },
    {
      id: 'p-1004', authorId: 'u-guest', universityId: null, facultyId: null, programId: null, topicId: null,
      content: 'Я пока не верифицирован, но уже читаю общую ленту. Очень удобно видеть, из какого университета пишет человек, а не сидеть в десяти разных чатах.',
      mediaUrls: [], likesCount: 13, commentsCount: 1, likedByMe: false, createdAt: iso(140),
    },
    {
      id: 'p-1005', authorId: 'u-admin', universityId: 'uni-itmo', facultyId: 'fac-itmo-fitip', programId: 'prog-itmo-fitip-se', topicId: null,
      content: 'Демо для документации: администратор может одобрять заявки на университеты, добавлять факультеты, а подтопики создаются одинаковыми для каждого факультета.',
      mediaUrls: [svgBanner('#005bbb', '#00c2ff', 'Admin flow')],
      likesCount: 31, commentsCount: 0, likedByMe: false, createdAt: iso(220),
    },
  ];

  let comments = [
    { id: 'c-1', postId: 'p-1001', authorId: 'u-msu-student', content: 'Разбор после задачи реально решает. Без него кажется, что всё понял, а потом снова та же ошибка.', likesCount: 8, likedByMe: false, createdAt: iso(11), parentCommentId: null },
    { id: 'c-2', postId: 'p-1001', authorId: 'u-teacher', content: 'Ещё полезно фиксировать не только решение, но и почему первая идея не подошла.', likesCount: 15, likedByMe: false, createdAt: iso(9), parentCommentId: null },
    { id: 'c-3', postId: 'p-1002', authorId: 'u-hse-student', content: 'Obsidian хорош для личного графа, Notion проще для группы. Для курса я бы выбрала Notion.', likesCount: 4, likedByMe: false, createdAt: iso(35), parentCommentId: null },
  ];

  let suggestions = [
    { id: 's-1', authorId: 'u-hse-student', text: 'Добавить фильтр по факультетам прямо в шапку ленты.', status: 'NEW', createdAt: iso(600) },
    { id: 's-2', authorId: 'u-msu-student', text: 'Сделать отдельную страницу с правилами оформления вопросов преподавателям.', status: 'NEW', createdAt: iso(760) },
  ];

  let universityProposals = [
    {
      id: 'up-1', authorId: 'u-guest', name: 'Московский физико-технический институт',
      shortName: 'МФТИ', subdomain: 'mipt', studentDomain: 'phystech.edu', employeeDomain: 'mipt.ru',
      city: null, description: null, iconUrl: svgIcon('МФТИ', '#1d4ed8'),
      status: 'NEW', createdAt: iso(920), reviewedBy: null, reviewedAt: null,
    },
  ];
  let facultyProposals = [
    { id: 'fp-1', authorId: 'u-hse-student', universityId: 'uni-hse', universityName: universities[0].name, universityShortName: universities[0].shortName, name: 'Факультет дизайна', shortName: 'ФД', status: 'NEW', createdAt: iso(500), reviewedBy: null, reviewedAt: null },
  ];
  let programProposals = [
    { id: 'pp-1', authorId: 'u-msu-student', universityId: 'uni-msu', universityName: universities[1].name, universityShortName: universities[1].shortName, facultyId: 'fac-msu-vmk', facultyName: faculties[2].name, facultyShortName: faculties[2].shortName, name: 'Фундаментальная информатика', shortName: 'ФИ', status: 'NEW', createdAt: iso(460), reviewedBy: null, reviewedAt: null },
  ];

  function pagePosts(source, variables = {}) {
    let result = [...source];
    if (variables.universityId) result = result.filter(p => p.universityId === variables.universityId);
    if (variables.facultyId) result = result.filter(p => p.facultyId === variables.facultyId);
    if (variables.programId) result = result.filter(p => p.programId === variables.programId);
    if (variables.topicId) result = result.filter(p => p.topicId === variables.topicId);
    result.sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt));
    return { posts: result.map(withAuthor), nextCursor: null, hasMore: false };
  }

  function requireUser() {
    const user = byId(users, currentUserId);
    if (!user) {
      const err = new Error('UNAUTHORIZED');
      err.isUnauth = true;
      throw err;
    }
    return user;
  }

  async function gql(query, variables = {}) {
    if (query.includes(' me ')) {
      return { me: currentUserId ? publicUser(byId(users, currentUserId)) : null };
    }
    if (query.includes('listUniversities')) return { listUniversities: universities };
    if (query.includes('listFaculties')) return { listFaculties: faculties.filter(f => f.universityId === variables.universityId) };
    if (query.includes('listPrograms')) return { listPrograms: programs.filter(p => p.facultyId === variables.facultyId) };
    if (query.includes('trendingFeed')) return { trendingFeed: pagePosts([...posts].sort((a, b) => b.likesCount - a.likesCount), variables) };
    if (query.includes('followingFeed')) {
      requireUser();
      return { followingFeed: pagePosts(posts.filter(p => p.authorId !== currentUserId), variables) };
    }
    if (query.includes('getUserByUsername')) {
      return { getUserByUsername: publicUser(users.find(u => u.username === variables.username)) };
    }
    if (query.includes('getUser(')) return { getUser: publicUser(byId(users, variables.id)) };
    if (query.includes('getUserPosts')) {
      return { getUserPosts: { posts: posts.filter(p => p.authorId === variables.userId).map(withAuthor), total: posts.filter(p => p.authorId === variables.userId).length } };
    }
    if (query.includes('getPost')) return { getPost: withAuthor(byId(posts, variables.id)) };
    if (query.includes('getComments')) return { getComments: { comments: comments.filter(c => c.postId === variables.postId), total: comments.filter(c => c.postId === variables.postId).length } };
    if (query.includes('adminImprovementSuggestions')) return { adminImprovementSuggestions: suggestions };
    if (query.includes('adminUniversityProposals')) return { adminUniversityProposals: universityProposals };
    if (query.includes('adminFacultyProposals')) return { adminFacultyProposals: facultyProposals };
    if (query.includes('adminProgramProposals')) return { adminProgramProposals: programProposals };

    if (query.includes('adminGrantAdmin')) {
      const actor = requireUser();
      if (actor.username !== 'kazenomi') throw new Error('Only kazenomi can grant admin privileges');
      const target = users.find(u => u.id === variables.targetUserId);
      if (!target) throw new Error('User not found');
      target.isAdmin = true;
      return { adminGrantAdmin: publicUser(target) };
    }

    if (query.includes('deleteAccount')) {
      const user = requireUser();
      users = users.filter(u => u.id !== user.id);
      posts = posts.filter(p => p.authorId !== user.id);
      comments = comments.filter(c => c.authorId !== user.id);
      suggestions = suggestions.filter(s => s.authorId !== user.id);
      universityProposals = universityProposals.filter(p => p.authorId !== user.id);
      facultyProposals = facultyProposals.filter(p => p.authorId !== user.id);
      programProposals = programProposals.filter(p => p.authorId !== user.id);
      currentUserId = '';
      localStorage.removeItem('um-mock-user');
      return { deleteAccount: { success: true } };
    }

    if (query.includes('updateProfile')) {
      const user = requireUser();
      Object.assign(user, variables.input);
      return { updateProfile: publicUser(user) };
    }
    if (query.includes('createPost')) {
      const user = requireUser();
      const post = {
        id: `p-${Date.now()}`, authorId: user.id,
        universityId: user.university?.id || null,
        facultyId: user.faculty?.id || null,
        programId: user.program?.id || null,
        topicId: null,
        content: variables.input.content || '',
        mediaUrls: variables.input.mediaUrls || [],
        likesCount: 0, commentsCount: 0, likedByMe: false, createdAt: new Date().toISOString(),
      };
      posts = [post, ...posts];
      return { createPost: post };
    }
    if (query.includes('createProgramProposal')) {
      const user = requireUser();
      const faculty = byId(faculties, variables.input.facultyId);
      const uni = byId(universities, faculty?.universityId);
      programProposals = [{
        id: `pp-${Date.now()}`, authorId: user.id,
        universityId: uni?.id || '',
        universityName: uni?.name || 'Университет',
        universityShortName: uni?.shortName || '',
        facultyId: variables.input.facultyId,
        facultyName: faculty?.name || 'Факультет',
        facultyShortName: faculty?.shortName || '',
        name: variables.input.name,
        shortName: variables.input.shortName,
        status: 'NEW', createdAt: new Date().toISOString(), reviewedBy: null, reviewedAt: null
      }, ...programProposals];
      return { createProgramProposal: { success: true } };
    }
    if (query.includes('createProgram')) {
      requireUser();
      const program = { id: `prog-${Date.now()}`, facultyId: variables.facultyId, name: variables.name, shortName: variables.shortName };
      programs.push(program);
      return { createProgram: program };
    }
    if (query.includes('likePost') || query.includes('unlikePost')) {
      requireUser();
      const post = byId(posts, variables.postId);
      if (post) post.likesCount = Math.max(0, post.likesCount + (query.includes('unlikePost') ? -1 : 1));
      return { [query.includes('unlikePost') ? 'unlikePost' : 'likePost']: { success: true } };
    }
    if (query.includes('likeComment') || query.includes('unlikeComment')) {
      requireUser();
      const comment = byId(comments, variables.commentId);
      if (comment) {
        const delta = query.includes('unlikeComment') ? -1 : 1;
        comment.likesCount = Math.max(0, (comment.likesCount || 0) + delta);
        comment.likedByMe = delta > 0;
      }
      return { [query.includes('unlikeComment') ? 'unlikeComment' : 'likeComment']: { success: true } };
    }
    if (query.includes('addComment')) {
      const user = requireUser();
      const comment = { id: `c-${Date.now()}`, postId: variables.postId, authorId: user.id, content: variables.content, likesCount: 0, likedByMe: false, createdAt: new Date().toISOString(), parentCommentId: variables.parentCommentId || null };
      comments = [comment, ...comments];
      const post = byId(posts, variables.postId);
      if (post) post.commentsCount += 1;
      return { addComment: comment };
    }
    if (query.includes('createImprovementSuggestion')) {
      const user = requireUser();
      suggestions = [{ id: `s-${Date.now()}`, authorId: user.id, text: variables.text, status: 'NEW', createdAt: new Date().toISOString() }, ...suggestions];
      return { createImprovementSuggestion: { success: true } };
    }
    if (query.includes('adminDeleteSuggestion')) {
      requireUser();
      suggestions = suggestions.filter(s => s.id !== variables.id);
      return { adminDeleteSuggestion: { success: true } };
    }
    if (query.includes('createUniversityProposal')) {
      const user = requireUser();
      universityProposals = [{ id: `up-${Date.now()}`, authorId: user.id, status: 'NEW', createdAt: new Date().toISOString(), ...variables.input }, ...universityProposals];
      return { createUniversityProposal: { success: true } };
    }
    if (query.includes('createFacultyProposal')) {
      const user = requireUser();
      const uni = byId(universities, variables.input.universityId);
      facultyProposals = [{
        id: `fp-${Date.now()}`, authorId: user.id,
        universityId: variables.input.universityId,
        universityName: uni?.name || 'Университет',
        universityShortName: uni?.shortName || '',
        name: variables.input.name,
        shortName: variables.input.shortName,
        status: 'NEW', createdAt: new Date().toISOString(), reviewedBy: null, reviewedAt: null
      }, ...facultyProposals];
      return { createFacultyProposal: { success: true } };
    }
    if (query.includes('adminReviewUniversityProposal')) {
      const admin = requireUser();
      const proposal = byId(universityProposals, variables.proposalId);
      if (proposal) {
        proposal.status = variables.status;
        proposal.reviewedBy = admin.id;
        proposal.reviewedAt = new Date().toISOString();
        if (variables.status === 'APPROVED') {
          universities.push({ id: `uni-${proposal.shortName.toLowerCase()}`, name: proposal.name, shortName: proposal.shortName, subdomain: proposal.subdomain, iconUrl: proposal.iconUrl });
        }
      }
      return { adminReviewUniversityProposal: { success: true } };
    }
    if (query.includes('adminReviewFacultyProposal')) {
      const admin = requireUser();
      const proposal = byId(facultyProposals, variables.proposalId);
      if (proposal) {
        proposal.status = variables.status;
        proposal.reviewedBy = admin.id;
        proposal.reviewedAt = new Date().toISOString();
        if (variables.status === 'APPROVED') {
          const faculty = { id: `fac-${Date.now()}`, universityId: proposal.universityId, name: proposal.name, shortName: proposal.shortName };
          faculties.push(faculty);
          const parent = { id: `topic-${faculty.id}`, universityId: faculty.universityId, facultyId: faculty.id, slug: `faculty-${faculty.id}`, name: faculty.shortName, isSystem: true };
          topics.push(parent, ...topicNames.map(([slug, name]) => ({ id: `topic-${faculty.id}-${slug}`, universityId: faculty.universityId, facultyId: faculty.id, parentTopicId: parent.id, slug, name, isSystem: true })));
        }
      }
      return { adminReviewFacultyProposal: { success: true } };
    }
    if (query.includes('adminReviewProgramProposal')) {
      const admin = requireUser();
      const proposal = byId(programProposals, variables.proposalId);
      if (proposal) {
        proposal.status = variables.status;
        proposal.reviewedBy = admin.id;
        proposal.reviewedAt = new Date().toISOString();
        if (variables.status === 'APPROVED') {
          programs.push({ id: `prog-${Date.now()}`, facultyId: proposal.facultyId, name: proposal.name, shortName: proposal.shortName });
        }
      }
      return { adminReviewProgramProposal: { success: true } };
    }
    if (query.includes('sendVerificationCode')) return { sendVerificationCode: { success: true, message: 'Демо-код: 000000' } };
    if (query.includes('verifyEmailCode')) {
      const user = requireUser();
      user.isStudentVerified = true;
      user.university ||= universities[0];
      user.faculty ||= faculties[0];
      user.program ||= programs.find(p => p.facultyId === user.faculty?.id) || null;
      return { verifyEmailCode: { success: true, error: null } };
    }
    if (query.includes('subscribe') || query.includes('unsubscribe') || query.includes('delete') || query.includes('edit')) {
      requireUser();
      return { subscribe: { success: true }, unsubscribe: { success: true }, deletePost: { success: true }, adminDeletePost: { success: true }, deleteComment: { success: true }, adminDeleteComment: { success: true }, editPost: { id: variables.postId, content: variables.input?.content || '' }, editComment: { id: variables.commentId, content: variables.content } };
    }

    return undefined;
  }

  function login(userId) {
    currentUserId = userId;
    localStorage.setItem('um-mock-user', userId);
    window.dispatchEvent(new CustomEvent('mock-login', { detail: publicUser(byId(users, userId)) }));
  }

  function logout() {
    currentUserId = '';
    localStorage.removeItem('um-mock-user');
    window.dispatchEvent(new CustomEvent('mock-login', { detail: null }));
  }

  window.MOCK = { enabled: false, users: users.map(publicUser), universities, gql, login, logout };
})();
