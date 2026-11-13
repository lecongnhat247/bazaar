import styles from "../admin.module.css";

export default async function LoginPage({ searchParams }: { searchParams: Promise<{ error?: string }> }) {
  const params = await searchParams;
  return (
    <main>
      <p className={styles.kicker}>Staff</p>
      <h1 className={styles.title}>Sign in with an admin or operator account.</h1>
      <p className={styles.lede}>
        The browser goes to Keycloak. Admin Portal keeps the access token in an HTTP-only cookie and does not accept an actor id from the page.
      </p>
      {params.error ? <p className={styles.error} role="alert">Sign-in did not finish. Try again.</p> : null}
      <p><a className={styles.button} href="/api/auth/login?return=/">Continue to sign in</a></p>
    </main>
  );
}
