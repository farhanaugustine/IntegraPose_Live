(() => {
  const header = document.querySelector('.site-header');
  const button = document.querySelector('.menu-button');
  const nav = document.querySelector('.nav-links');
  const syncHeader = () => header?.classList.toggle('scrolled', scrollY > 12);
  syncHeader();
  addEventListener('scroll', syncHeader, { passive: true });

  button?.addEventListener('click', () => {
    const open = nav?.classList.toggle('open') ?? false;
    button.setAttribute('aria-expanded', String(open));
  });
  nav?.querySelectorAll('a').forEach(link => link.addEventListener('click', () => {
    nav.classList.remove('open');
    button?.setAttribute('aria-expanded', 'false');
  }));

  const repositoryUrl = () => {
    if (!location.hostname.endsWith('.github.io')) return 'https://github.com/';
    const owner = location.hostname.slice(0, -'.github.io'.length);
    const repo = location.pathname.split('/').filter(Boolean)[0];
    return repo ? 'https://github.com/' + owner + '/' + repo : 'https://github.com/' + owner;
  };
  document.querySelectorAll('[data-repo-link]').forEach(link => { link.href = repositoryUrl(); });

  document.querySelectorAll('pre').forEach(pre => {
    const wrap = document.createElement('div');
    wrap.className = 'code-block';
    pre.parentNode.insertBefore(wrap, pre);
    wrap.appendChild(pre);
    const copy = document.createElement('button');
    copy.className = 'copy-button';
    copy.type = 'button';
    copy.textContent = 'Copy';
    copy.addEventListener('click', async () => {
      await navigator.clipboard.writeText(pre.innerText);
      copy.textContent = 'Copied';
      setTimeout(() => { copy.textContent = 'Copy'; }, 1400);
    });
    wrap.appendChild(copy);
  });

  const sections = [...document.querySelectorAll('.docs-main section[id]')];
  const links = [...document.querySelectorAll('.docs-sidebar a[href^="#"]')];
  if (sections.length && links.length && 'IntersectionObserver' in window) {
    const observer = new IntersectionObserver(entries => {
      const visible = entries.filter(x => x.isIntersecting).sort((a,b) => b.intersectionRatio-a.intersectionRatio)[0];
      if (visible) links.forEach(link => link.classList.toggle('active', link.hash === '#' + visible.target.id));
    }, { rootMargin: '-18% 0px -65% 0px', threshold: [0,.25,.75] });
    sections.forEach(section => observer.observe(section));
  }

  if (matchMedia('(prefers-reduced-motion: reduce)').matches) {
    document.querySelectorAll('video[autoplay]').forEach(video => video.pause());
  }

  const demoVideo = document.querySelector('#demo-video');
  const demoSource = demoVideo?.querySelector('source');
  const demoTabs = [...document.querySelectorAll('.demo-tab')];
  const demoLabel = document.querySelector('#demo-label');
  const demoResult = document.querySelector('#demo-result');
  const demoSpec = document.querySelector('#demo-spec');
  const demoCredits = [...document.querySelectorAll('.demo-credits .dataset-citation')];
  demoTabs.forEach(tab => tab.addEventListener('click', () => {
    if (!demoVideo || !demoSource || tab.classList.contains('active')) return;
    demoTabs.forEach(item => {
      const selected = item === tab;
      item.classList.toggle('active', selected);
      item.setAttribute('aria-selected', String(selected));
    });
    demoVideo.pause();
    demoSource.src = tab.dataset.src;
    demoVideo.poster = tab.dataset.poster;
    demoVideo.setAttribute('aria-label', tab.dataset.alt);
    demoVideo.load();
    demoLabel.textContent = tab.dataset.label;
    demoResult.innerHTML = '<strong>Result:</strong> ' + tab.dataset.result;
    demoSpec.textContent = tab.dataset.spec;
    demoCredits.forEach(credit => credit.classList.toggle('active', credit.id === tab.dataset.credit));
    demoVideo.play().catch(() => {});
  }));

  document.querySelectorAll('[data-year]').forEach(node => { node.textContent = new Date().getFullYear(); });
})();
