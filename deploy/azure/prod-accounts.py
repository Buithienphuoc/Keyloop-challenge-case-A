#!/usr/bin/env python3
"""Generates the production passwords of the demo accounts. Nothing it writes belongs in git.

    python3 deploy/azure/prod-accounts.py OUT_DIR

writes two files into OUT_DIR:
  prod-accounts.txt              each account with its new password: keep it private
  PROD_SEED_ACCOUNTS.secret.txt  the same accounts with only a PBKDF2 hash of each password, the
                                 value of the GitHub secret PROD_SEED_ACCOUNTS (Settings -> Secrets
                                 and variables -> Actions). The deploy job hands it to the API as
                                 SEED_ACCOUNT_PASSWORDS, so the plain passwords never leave OUT_DIR.

Run it on your own computer (Python 3, standard library only), so the plain passwords exist
nowhere else. The hashes use the API's own format (scheduler.modules.identity.Passwords).

Changing the passwords of the live site:
  1. run this script again,
  2. replace the GitHub secret PROD_SEED_ACCOUNTS with the new PROD_SEED_ACCOUNTS.secret.txt,
  3. run the CI/CD workflow on master (Actions -> CI/CD -> Run workflow).
On start-up the API sets every listed account to its new password and signs it out everywhere.
"""
import base64
import hashlib
import secrets
import string
import sys
from pathlib import Path

# Every account backend/app/scheduler/Seed.scala creates. Keep the two lists in step.
ACCOUNTS = [
    ("sysadmin@demo.vn", "System manager"),
    ("admin.d1@demo.vn", "Dealership admin, District 1"),
    ("advisor.d1@demo.vn", "Service advisor, District 1"),
    ("controller.d1@demo.vn", "Workshop controller, District 1"),
    ("parts.d1@demo.vn", "Parts staff, District 1"),
    ("tech.hung@demo.vn", "Technician, District 1"),
    ("admin.td@demo.vn", "Dealership admin, Thu Duc"),
    ("advisor.td@demo.vn", "Service advisor, Thu Duc"),
    ("controller.td@demo.vn", "Workshop controller, Thu Duc"),
    ("parts.td@demo.vn", "Parts staff, Thu Duc"),
    ("tech.tam@demo.vn", "Technician, Thu Duc"),
    ("customer@demo.vn", "Customer"),
    *[(f"walkin{i}@demo.vn", "Customer (calendar filler)") for i in range(1, 6)],
]

ITERATIONS = 210_000


def password() -> str:
    """Four groups of five letters and digits (about 119 bits), easy to read and type."""
    alphabet = string.ascii_letters + string.digits
    while True:
        groups = ["".join(secrets.choice(alphabet) for _ in range(5)) for _ in range(4)]
        pw = "-".join(groups)
        if any(c.islower() for c in pw) and any(c.isupper() for c in pw) and any(c.isdigit() for c in pw):
            return pw


def pbkdf2(pw: str) -> str:
    b64 = lambda b: base64.urlsafe_b64encode(b).rstrip(b"=").decode()
    salt = secrets.token_bytes(16)
    key = hashlib.pbkdf2_hmac("sha512", pw.encode(), salt, ITERATIONS, dklen=64)
    return f"pbkdf2-sha512${ITERATIONS}${b64(salt)}${b64(key)}"


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    rows = [(email, role, password()) for email, role in ACCOUNTS]

    width = max(len(e) for e, _, _ in rows)
    lines = ["Service Scheduler - production accounts", "Keep this file private. Sign in at the deployed site's /login.", ""]
    lines += [f"{email:<{width}}  {pw}  {role}" for email, role, pw in rows]
    (out / "prod-accounts.txt").write_text("\n".join(lines) + "\n")

    # ";" separates entries: a Container Apps secret is one line.
    (out / "PROD_SEED_ACCOUNTS.secret.txt").write_text(";".join(f"{e}={pbkdf2(pw)}" for e, _, pw in rows) + "\n")
    print(f"Wrote {out / 'prod-accounts.txt'} and {out / 'PROD_SEED_ACCOUNTS.secret.txt'}")


if __name__ == "__main__":
    main()
