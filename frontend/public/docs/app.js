async function loadLocale(locale) {
  const response = await fetch(`/docs/i18n/${locale}.json`, { cache: 'no-store' });
  if (!response.ok) throw new Error(`Failed to load locale: ${locale}`);
  return response.json();
}

function render(data) {
  document.documentElement.lang = data.lang;
  document.title = data.meta.title;
  document.getElementById('title').textContent = data.hero.title;
  document.getElementById('subtitle').textContent = data.hero.subtitle;
  document.getElementById('updated').textContent = data.hero.updated;
  document.getElementById('quickLinksTitle').textContent = data.quickLinksTitle;
  document.getElementById('faqTitle').textContent = data.faqTitle;

  const linksRoot = document.getElementById('quickLinks');
  linksRoot.innerHTML = '';
  data.quickLinks.forEach((item) => {
    const tag = document.createElement('span');
    tag.className = 'chip';
    tag.textContent = item;
    linksRoot.appendChild(tag);
  });

  const sectionsRoot = document.getElementById('sections');
  sectionsRoot.innerHTML = '';
  data.sections.forEach((section) => {
    const article = document.createElement('article');
    article.className = 'section';
    const bullets = section.bullets.map((b) => `<li>${b}</li>`).join('');
    article.innerHTML = `<h3>${section.title}</h3><p>${section.description}</p><ul>${bullets}</ul>`;
    sectionsRoot.appendChild(article);
  });

  const faqRoot = document.getElementById('faq');
  faqRoot.innerHTML = '';
  data.faq.forEach((entry) => {
    const block = document.createElement('div');
    block.className = 'faq-item';
    block.innerHTML = `<h3>${entry.question}</h3><p>${entry.answer}</p>`;
    faqRoot.appendChild(block);
  });
}

function detectLocale() {
  const stored = localStorage.getItem('docs-lang');
  if (stored === 'en' || stored === 'de') return stored;
  return (navigator.language || 'en').toLowerCase().startsWith('de') ? 'de' : 'en';
}

async function bootstrap() {
  const selector = document.getElementById('lang');
  let locale = detectLocale();
  selector.value = locale;

  const setLocale = async (next) => {
    locale = next;
    localStorage.setItem('docs-lang', locale);
    const payload = await loadLocale(locale);
    render(payload);
  };

  selector.addEventListener('change', () => setLocale(selector.value));
  await setLocale(locale);
}

bootstrap().catch((error) => {
  document.body.innerHTML = `<pre>Docs failed to load: ${error.message}</pre>`;
});
