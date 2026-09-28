import { Trans, useTranslation } from 'react-i18next';

export function App() {
  const { t } = useTranslation();
  const count = 3;
  return (
    <main>
      <h1>{t('app.title')}</h1>
      <p>{t('app.subtitle')}</p>

      <nav>
        <a href="/settings">{t('nav.settings')}</a>
        <a href="/profile">{t('nav.profile')}</a>
        <a href="/billing" title="Billing">{t('nav.billing')}</a>
      </nav>

      <form>
        <label>{t('form.email')}</label>
        <input placeholder="Email address" />
        <span>{t('form.required')}</span>
      </form>

      <button className="cta">{t('cta.save')}</button>
      <button>{t('cta.cancel')}</button>
      <button>{t('cta.delete')}</button>

      <p>{t('items.count', { count })}</p>
      <p>{t('dialog.deleteTitle', { name: 'Acme' })}</p>
      <p>{t('dialog.deleteBody', { name: 'Acme' })}</p>
      <p>{t('message.welcome', { name: 'Ada' })}</p>
      <Trans i18nKey="legal.terms" />

      <button>Get started for free</button>
      <p>Loading your dashboard…</p>
      <span title="Settings">⚙️</span>
      <a href="/docs">Read the documentation</a>
      {t('debug.panel')}
    </main>
  );
}
