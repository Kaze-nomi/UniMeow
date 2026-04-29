
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
          Проект разрабатывается как дипломная работа. Если хотите помочь — напишите нам через <span onClick={() => window.dispatchEvent(new CustomEvent('open-modal', { detail: 'suggest' }))} style={{ color: 'var(--accent)', cursor: 'pointer' }}>Обратную связь</span>.
        </p>
        <div style={{ height: 1, background: 'var(--border)' }} />
        <div style={{ fontSize: 13, color: 'var(--text-muted)', lineHeight: 1.6 }}>
          <div style={{ fontWeight: 700, marginBottom: 6, fontSize: 14, color: 'var(--text)' }}>Стек технологий</div>
          React · Spring WebFlux · gRPC · PostgreSQL · MinIO · GraphQL
        </div>
      </div>
    </div>
  );
}
Object.assign(window, { AboutPage });
