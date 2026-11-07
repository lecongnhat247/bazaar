import styles from "../shop.module.css";

export default async function LoginPage({ searchParams }: { searchParams: Promise<{ error?: string }> }) {
  const params = await searchParams;
  return (
    <main className={styles.page}>
      <p className={styles.kicker}>Account</p>
      <h1 className={styles.title}>Sign in with the local shop account.</h1>
      <p className={styles.lede}>
        The browser is sent to Keycloak. The storefront keeps the access token in an HTTP-only cookie and does not put it in local storage.
      </p>
      {params.error ? <p className={styles.error}>Sign-in did not finish. Try again.</p> : null}
      <a className={styles.button} href="/api/auth/login?return=/">Continue to sign in</a>
    </main>
  );
}
