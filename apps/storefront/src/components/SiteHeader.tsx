import styles from "./SiteHeader.module.css";

const links = [
  { href: "#overview", label: "Overview" },
  { href: "#marketplace", label: "Marketplace" },
  { href: "#status", label: "Status" },
];

export function SiteHeader() {
  return (
    <header className={styles.header}>
      <a className={styles.brand} href="#overview">
        BAZAAR
      </a>
      <nav className={styles.nav} aria-label="Page">
        {links.map((link) => (
          <a key={link.href} href={link.href}>
            {link.label}
          </a>
        ))}
      </nav>
    </header>
  );
}
