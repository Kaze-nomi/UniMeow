
function AboutPage({ onNavigate }) {
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
        <button onClick={() => onNavigate('/')} style={{ background: 'none', border: 'none', cursor: 'pointer', padding: 6, color: 'var(--text)', display: 'inline-flex' }}>
          <BackArrowIcon size={20} color="var(--text)" />
        </button>
        <h2 style={{ margin: 0, fontSize: 20, fontWeight: 800 }}>О проекте</h2>
      </div>
      <div style={{ padding: '32px 20px', display: 'flex', flexDirection: 'column', gap: 18, maxWidth: 560 }}>
        <div style={{ display: 'flex', justifyContent: 'center', marginBottom: 8 }}>
          <CatLogo size={72} />
        </div>
        <h1 style={{ fontSize: 28, fontWeight: 800, textAlign: 'center', margin: 0 }}>UniMeow</h1>
        <p style={{ fontSize: 15, color: 'var(--text-muted)', textAlign: 'center', margin: 0 }}>Университетская социальная сеть</p>
        <div style={{ height: 1, background: 'var(--border)' }} />
        <p style={{ fontSize: 15, lineHeight: 1.7, margin: 0 }}>
          UniMeow — пространство для студентов и преподавателей, где можно обсуждать учёбу, делиться новостями факультета и общаться с людьми из своего ВУЗа.
        </p>
        <p style={{ fontSize: 15, lineHeight: 1.7, margin: 0 }}>
          Верифицированные студенты и сотрудники отмечены специальными значками, чтобы вы всегда знали, кому доверять.
        </p>
        <p style={{ fontSize: 15, lineHeight: 1.7, margin: 0 }}>
          Если хотите помочь — напишите через <span onClick={() => window.dispatchEvent(new CustomEvent('open-modal', { detail: 'suggest' }))} style={{ color: 'var(--accent)', cursor: 'pointer' }}>Обратную связь</span>.
        </p>
        <p style={{ fontSize: 15, lineHeight: 1.7, margin: 0 }}>
          Исходный код проекта на GitHub: <a href="https://github.com/Kaze-nomi/UniMeow" target="_blank" rel="noopener noreferrer" style={{ color: 'var(--accent)', textDecoration: 'none', fontWeight: 600 }}>github.com/Kaze-nomi/UniMeow</a>
        </p>
        <div style={{ height: 1, background: 'var(--border)' }} />
        <section style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          <h2 style={{ margin: 0, fontSize: 18, fontWeight: 800 }}>Документы</h2>
          <button onClick={() => onNavigate('/privacy')} className="um-side-nav" style={docLinkStyle}>
            Политика обработки персональных данных
          </button>
          <button onClick={() => onNavigate('/consent')} className="um-side-nav" style={docLinkStyle}>
            Согласие на обработку персональных данных
          </button>
        </section>
      </div>
    </div>
  );
}

const docLinkStyle = {
  width: '100%',
  padding: '12px 14px',
  borderRadius: 10,
  border: '1px solid var(--border)',
  background: 'var(--surface)',
  color: 'var(--accent)',
  fontFamily: 'inherit',
  fontSize: 14,
  fontWeight: 700,
  cursor: 'pointer',
  textAlign: 'left',
};

function LegalPageLayout({ title, subtitle, onNavigate, children }) {
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
        <button onClick={() => onNavigate('/about')} style={{ background: 'none', border: 'none', cursor: 'pointer', padding: 6, color: 'var(--text)', display: 'inline-flex' }}>
          <BackArrowIcon size={20} color="var(--text)" />
        </button>
        <div>
          <h2 style={{ margin: 0, fontSize: 20, fontWeight: 800 }}>{title}</h2>
          {subtitle && <div style={{ marginTop: 2, fontSize: 12, color: 'var(--text-muted)' }}>{subtitle}</div>}
        </div>
      </div>
      <article style={{ padding: '24px 20px 40px', maxWidth: 720, display: 'flex', flexDirection: 'column', gap: 18 }}>
        {children}
      </article>
    </div>
  );
}

function LegalSection({ title, children }) {
  return (
    <section style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      <h3 style={{ margin: 0, fontSize: 17, fontWeight: 800 }}>{title}</h3>
      <div style={{ fontSize: 14.5, lineHeight: 1.65, color: 'var(--text)' }}>{children}</div>
    </section>
  );
}

function LegalList({ items }) {
  return (
    <ul style={{ margin: 0, paddingLeft: 20, display: 'flex', flexDirection: 'column', gap: 6 }}>
      {items.map((item, idx) => <li key={idx}>{item}</li>)}
    </ul>
  );
}

function PrivacyPage({ onNavigate }) {
  return (
    <LegalPageLayout title="Политика обработки персональных данных" subtitle="Редакция от 13 мая 2026 года" onNavigate={onNavigate}>
      <LegalSection title="1. Оператор и сервис">
        UniMeow обрабатывает персональные данные пользователей социальной сети UniMeow. Оператором обработки является администратор проекта UniMeow. Контактный email для обращений по персональным данным: <a href="mailto:contact@unimeow.ru" style={{ color: 'var(--accent)', fontWeight: 600 }}>contact@unimeow.ru</a>.
      </LegalSection>

      <LegalSection title="2. Какие данные обрабатываются">
        <LegalList items={[
          'данные Google OAuth: Google email, имя, фамилия и аватар, если они переданы Google при входе;',
          'данные профиля: username, имя, фамилия, аватар, обложка, статус, bio, дата регистрации;',
          'университетские данные: университетский email, ВУЗ, факультет, программа, курс, уровень образования, год выпуска и статус верификации;',
          'пользовательский контент: посты, комментарии, медиафайлы, лайки, подписки, уведомления и заявки;',
          'модерационные данные: статус блокировки, срок блокировки, причина блокировки и действия администратора;',
          'технические данные: cookies авторизации, сведения о запросах, ошибки, IP-адреса в серверных логах, данные браузера и события аналитики Яндекс.Метрики.',
        ]} />
      </LegalSection>

      <LegalSection title="3. Зачем нужны данные">
        <LegalList items={[
          'создание аккаунта, вход через Google и поддержание сессии;',
          'работа профиля, постов, комментариев, лайков, подписок, лент и уведомлений;',
          'подтверждение университетского статуса через email;',
          'загрузка и показ аватаров, обложек, изображений и видео;',
          'модерация, защита от нарушений, блокировки и предотвращение повторного входа бессрочно заблокированных аккаунтов;',
          'исправление ошибок, безопасность, статистика использования и развитие проекта.',
        ]} />
      </LegalSection>

      <LegalSection title="4. Правовые основания">
        Данные обрабатываются на основании согласия пользователя, действий пользователя по регистрации и использованию функций UniMeow, необходимости исполнения пользовательского запроса, а также законных обязанностей оператора по безопасности, модерации, хранению технических журналов и ответам на обращения субъектов персональных данных.
      </LegalSection>

      <LegalSection title="5. Публичные данные">
        Профиль, username, имя, фамилия, аватар, статус, bio, университетская принадлежность, посты, комментарии, медиа, лайки и подписки могут быть видны другим пользователям или неограниченному кругу лиц, если пользователь сам публикует их в UniMeow или делает доступными через функции приложения.
      </LegalSection>

      <LegalSection title="6. Передача и внешние сервисы">
        Данные не продаются. Для работы UniMeow используются Google OAuth для входа, Яндекс.Метрика для аналитики, почтовый сервис для кодов подтверждения, хостинг, базы данных, Redis, Kafka и MinIO для хранения и обработки данных внутри сервиса.
      </LegalSection>

      <LegalSection title="7. Cookies и аналитика">
        UniMeow использует cookies авторизации `ACCESS_TOKEN` и `REFRESH_TOKEN`. Яндекс.Метрика может использовать cookies и технические данные браузера для статистики посещений и работы интерфейса.
      </LegalSection>

      <LegalSection title="8. Трансграничная обработка">
        При использовании Google OAuth, Яндекс.Метрики и иных внешних технических сервисов отдельные технические данные могут обрабатываться за пределами Российской Федерации. Основные данные аккаунтов и контент UniMeow обрабатываются в инфраструктуре сервиса.
      </LegalSection>

      <LegalSection title="9. Срок хранения и удаление">
        Данные хранятся, пока есть аккаунт или пока они нужны для целей безопасности и модерации. Пользователь может удалить аккаунт в настройках. При удалении очищаются профиль, сессии, подписки, посты, комментарии, лайки, уведомления и следы в лентах. Для бессрочно заблокированных аккаунтов может сохраняться минимальная запись о Google email и причине блокировки, чтобы предотвратить повторную регистрацию.
      </LegalSection>

      <LegalSection title="10. Права пользователя">
        Пользователь может обновить профиль, удалить аккаунт, запросить удаление данных или отозвать согласие на обработку через настройки, обратную связь или email <a href="mailto:contact@unimeow.ru" style={{ color: 'var(--accent)', fontWeight: 600 }}>contact@unimeow.ru</a>. Отзыв согласия может привести к невозможности пользоваться аккаунтом.
      </LegalSection>

      <LegalSection title="11. Защита данных">
        Доступ к данным ограничивается авторизацией, cookies авторизации имеют httpOnly-настройки, межсервисные вызовы выполняются через внутренние API, а административные операции доступны только администраторам.
      </LegalSection>
    </LegalPageLayout>
  );
}

function ConsentPage({ onNavigate }) {
  return (
    <LegalPageLayout title="Согласие на обработку персональных данных" subtitle="Редакция от 13 мая 2026 года" onNavigate={onNavigate}>
      <LegalSection title="Согласие">
        Пользователь, входя в UniMeow через Google, заполняя профиль, подтверждая университетский email, публикуя контент или используя функции приложения, даёт оператору UniMeow согласие на обработку персональных данных на условиях, описанных ниже и в Политике обработки персональных данных.
      </LegalSection>

      <LegalSection title="1. Данные">
        Согласие распространяется на Google email и данные профиля Google, данные профиля UniMeow, университетские данные, пользовательский контент, медиафайлы, лайки, подписки, уведомления, заявки, модерационные сведения, cookies авторизации, технические логи и данные аналитики.
      </LegalSection>

      <LegalSection title="2. Цели обработки">
        Данные обрабатываются для регистрации и входа, работы профиля и социальной сети, университетской верификации, публикации и отображения контента, уведомлений, модерации, безопасности, удаления аккаунта, исправления ошибок и анализа работы сервиса.
      </LegalSection>

      <LegalSection title="3. Действия с данными">
        Оператор может собирать, записывать, хранить, уточнять, использовать, передавать внутри инфраструктуры сервиса, обезличивать, блокировать, удалять и уничтожать персональные данные.
      </LegalSection>

      <LegalSection title="4. Внешние сервисы">
        Пользователь согласен, что для входа используется Google OAuth, для аналитики может использоваться Яндекс.Метрика, а для отправки кодов подтверждения, хранения файлов и работы приложения используются технические сервисы и инфраструктура UniMeow.
      </LegalSection>

      <LegalSection title="5. Публичное распространение">
        Пользователь отдельно соглашается, что опубликованные им профильные данные, посты, комментарии, медиафайлы, лайки, подписки и университетская принадлежность могут быть показаны другим пользователям и посетителям UniMeow в пределах функций приложения. Пользователь не должен публиковать данные, которые не хочет делать общедоступными.
      </LegalSection>

      <LegalSection title="6. Срок действия и отзыв">
        Согласие действует до удаления аккаунта или отзыва согласия. Отозвать согласие можно удалением аккаунта в настройках или обращением к оператору через email <a href="mailto:contact@unimeow.ru" style={{ color: 'var(--accent)', fontWeight: 600 }}>contact@unimeow.ru</a>. После отзыва согласия использование аккаунта может быть недоступно.
      </LegalSection>
    </LegalPageLayout>
  );
}

Object.assign(window, { AboutPage, PrivacyPage, ConsentPage });
