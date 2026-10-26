"""Generates hexagonal-architecture.svg (rendered to PNG with Playwright). Positions are computed, not hand-placed."""
import html
import math

W, H = 2500, 1950
CX, CY = 1250, 900
R_OUT, R_APP, R_DOM = 800, 470, 200
INK, PAPER, BLUE, HIVIS, GREY, GREEN = '#1F2A30', '#F3F5F4', '#1D4E89', '#F2C230', '#56636B', '#2E7D4F'
out = []


def hexagon(r, fill, stroke, extra=''):
    pts = ' '.join(f'{CX + r * math.cos(math.radians(a)):.1f},{CY + r * math.sin(math.radians(a)):.1f}' for a in range(30, 390, 60))
    out.append(f'<polygon points="{pts}" fill="{fill}" stroke="{stroke}" stroke-width="2.5" {extra}/>')


def text(x, y, s, size=15, weight=400, fill=INK, anchor='middle', italic=False):
    st = ' font-style="italic"' if italic else ''
    out.append(f'<text x="{x}" y="{y}" font-size="{size}" font-weight="{weight}" fill="{fill}" text-anchor="{anchor}"{st}>{html.escape(s)}</text>')


def box(x, y, w, title, sub=None, fill='#fff', stroke=INK, title_fill=INK, h=None, size=14):
    h = h or (44 if sub else 32)
    out.append(f'<rect x="{x - w / 2}" y="{y - h / 2}" width="{w}" height="{h}" rx="7" fill="{fill}" stroke="{stroke}" stroke-width="1.5"/>')
    if sub:
        text(x, y - 3, title, size, 700, title_fill)
        text(x, y + 14, sub, 11, 400, GREY)
    else:
        text(x, y + 5, title, size, 700, title_fill)
    return (x, y, w, h)


def port(x, y, w, label, sub):
    out.append(f'<rect x="{x - w / 2}" y="{y - 20}" width="{w}" height="40" rx="4" fill="{HIVIS}" stroke="{INK}" stroke-width="1.5"/>')
    text(x, y - 3, label, 12, 700)
    text(x, y + 13, sub, 10, 400, INK)


def arrow(x1, y1, x2, y2, color=INK, dash=False):
    d = ' stroke-dasharray="8 6"' if dash else ''
    out.append(f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{color}" stroke-width="1.8"{d} marker-end="url(#ink)"/>')


SIDE_APP = R_APP * math.sqrt(3) / 2  # x offset of the application hexagon's vertical sides
SIDE_OUT = R_OUT * math.sqrt(3) / 2
COL = (SIDE_APP + 105 + SIDE_OUT) / 2 + 6  # centre of the adapter columns
ROWS = [-225 + 50 * i for i in range(10)]  # ten rows that fit the vertical sides

out.append(f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" font-family="Helvetica, Arial, sans-serif">')
out.append(f'''<defs><marker id="ink" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="{INK}"/></marker></defs>
<rect width="{W}" height="{H}" fill="#fff"/>''')
text(CX, 52, 'Hexagonal architecture (ports and adapters) of the Scala / Play backend, as built', 30, 700)
text(CX, 84, 'Dependencies point inward: adapters → ports → application → domain. Yellow tabs are ports. AppComponents (AppLoader.scala) is the only place that connects an adapter to a port.', 15, 400, GREY)

hexagon(R_OUT, PAPER, GREY, 'stroke-dasharray="10 6"')
hexagon(R_APP, '#E4ECF5', BLUE)
hexagon(R_DOM, BLUE, INK)

text(CX, CY - R_OUT + 230, 'ADAPTERS', 18, 700, GREY)
text(CX, CY - R_OUT + 254, 'modules/*/adapters/*Controller.scala (driving) · modules/*/adapters/Pg*.scala (driven) · shared/', 13, 400, GREY)
text(CX, CY - R_APP + 88, 'APPLICATION  (use cases: modules/*/application/)', 15, 700, BLUE)

# domain core
text(CX, CY - 110, 'DOMAIN', 20, 700, '#fff')
text(CX, CY - 88, 'pure, no I/O', 13, 400, '#DCE6F2', italic=True)
for i, (f, d) in enumerate([('Availability.scala', 'slots, best-fit allocation, shifts'), ('Lifecycle.scala', 'status machine, role rules'),
                            ('Compatibility.scala · Types.scala', 'bay type ↔ vehicle, values'), ('<module>/Domain.scala', 'catalog, vehicles, consultation, identity')]):
    text(CX, CY - 50 + i * 40, f, 14, 700, '#fff')
    text(CX, CY - 33 + i * 40, d, 11, 400, '#DCE6F2')

# application services: two rows above the core, two below
svc = dict(fill='#fff', stroke=BLUE, title_fill=BLUE, size=13)
for x, name in [(-225, 'AvailabilityService'), (-75, 'BookingService'), (75, 'AppointmentService'), (225, 'DispatchService')]:
    box(CX + x, CY - 280, 140, name, size=12, **{k: v for k, v in svc.items() if k != 'size'})
box(CX - 60, CY - 332, 220, 'WorkshopContext', 'shared rules: requirement, dealership', **svc)
box(CX + 130, CY - 332, 150, 'HoldSweeper', 'releases lapsed holds', **svc)
for x, name in [(-225, 'ConsultationService'), (-75, 'VehicleService'), (75, 'IdentityService'), (225, 'PartsService')]:
    box(CX + x, CY + 280, 140, name, size=12, **{k: v for k, v in svc.items() if k != 'size'})
for x, name in [(-160, 'WorkshopAdminService'), (0, 'TenantService'), (160, 'PackageAdminService')]:
    box(CX + x, CY + 330, 150, name, size=12, **{k: v for k, v in svc.items() if k != 'size'})
arrow(CX, CY - 264, CX, CY - R_DOM - 3)
arrow(CX, CY + 264, CX, CY + R_DOM + 3)

# driving side: one tall use-case port, nine HTTP adapters
px = CX - SIDE_APP
out.append(f'<rect x="{px - 30}" y="{CY - 245}" width="60" height="490" rx="5" fill="{HIVIS}" stroke="{INK}" stroke-width="1.5"/>')
out.append(f'<text transform="translate({px - 3},{CY}) rotate(-90)" font-size="16" font-weight="700" fill="{INK}" text-anchor="middle">Use-case API</text>')
out.append(f'<text transform="translate({px + 15},{CY}) rotate(-90)" font-size="11" fill="{INK}" text-anchor="middle">service methods + AuthContext (validated input)</text>')
left = [('SchedulingController', 'availability, hold, book, move'), ('ConsultationController', 'requests, advisor queue'), ('IdentityController', 'register, login, logout'),
        ('Guard (auth)', 'Authenticator port, roles'), ('VehicleController', 'my vehicles'), ('AdminController', 'bays, technicians, staff, hours'),
        ('SystemController', 'onboard dealerships, packages'), ('CatalogController', 'dealerships, packages'), ('PartsController', 'stock, parts orders'),
        ('/metrics · /health', 'Prometheus client, DB ping')]
for dy, (t, s_) in zip(ROWS, left):
    box(CX - COL, CY + dy, 210, t, s_)
    if t != '/metrics · /health':
        arrow(CX - COL + 105, CY + dy, px - 32, CY + dy)

# driven side: port tab + adapter per row
right = [('ResourceCalendar', 'AppointmentReader · AppointmentWriter', 'PgSchedulingStore', 'EXCLUDE errors → SlotTaken'),
         ('ConsultationBookingPort', 'declared by scheduling', 'ConsultationService', 'in-process, no SQL'),
         ('CatalogReader', 'dealerships, packages, skills', 'PgCatalogRepo', ''),
         ('VehicleRepository', 'OwnedVehicles for others', 'PgVehicleRepo', ''),
         ('ConsultationRepository', '', 'PgConsultationRepo', ''),
         ('UserRepository', 'SessionRepository', 'PgIdentityRepo', 'one class, two ports'),
         ('WorkshopRepository', 'TenantRepository · PackageRepository', 'PgWorkshopRepo · PgTenantRepo', 'PgPackageRepo'),
         ('PartsRepository', 'stock never below zero', 'PgPartsRepo', 'CHECK violation → OutOfStock'),
         ('Db (unit of work)', 'run, tx → TransientStorageError', 'PgDb (HikariCP)', 'slow-query log, metrics'),
         ('Clock · Log · Tracing', '() => Instant, Log, spans', 'Logback JSON · OpenTelemetry', 'TestClock in tests')]
for dy, (pl, ps, al, as_) in zip(ROWS, right):
    port(CX + SIDE_APP, CY + dy, 230, pl, ps or ' ')
    in_process = al == 'ConsultationService'
    box(CX + COL + 40, CY + dy, 230, al, as_ or None, fill='#E4ECF5' if in_process else '#fff', stroke=BLUE if in_process else INK,
        title_fill=BLUE if in_process else INK, size=13)
    arrow(CX + COL + 40 - 115, CY + dy, CX + SIDE_APP + 117, CY + dy)

# outside: actors and systems
EXL, EXR = 165, W - 150
for dy, t, s_, row in [(-220, 'React SPA', 'Bearer, Idempotency-Key', 0), (-110, 'Swagger UI', '/docs, openapi.yaml', 2),
                       (0, 'ScalaTest API tests', 'in-process requests, real PostgreSQL', 4), (220, 'Prometheus', 'scrapes /metrics', 9)]:
    box(EXL, CY + dy, 230, t, s_, fill=PAPER)
    arrow(EXL + 115, CY + dy, CX - COL - 107, CY + ROWS[row])
box(EXL, CY + 420, 230, 'Unit tests on fakes', 'BookingServiceFakesSpec, domain, props', fill=PAPER)
out.append(f'<path d="M{EXL + 115},{CY + 420} C {CX - 520},{CY + 520} {CX - 300},{CY + 420} {CX - 225},{CY + 298}" fill="none" stroke="{INK}" stroke-width="1.8" stroke-dasharray="3 5" marker-end="url(#ink)"/>')
text(CX - 560, CY + 505, 'services with in-memory ports, domain functions directly', 12, 400, GREY)

box(EXR, CY - 60, 230, 'PostgreSQL', 'EXCLUDE gist: bay, technician, vehicle', fill=PAPER, h=60)
box(EXR, CY + 220, 230, 'stdout', 'JSON lines → log store', fill=PAPER)
box(EXR, CY + 330, 230, 'Jaeger (OTLP)', 'request, booking and SQL spans', fill=PAPER)
arrow(CX + COL + 155, CY + ROWS[9], EXR - 117, CY + 330)
for row in [0, 2, 3, 4, 5, 6, 7, 8]:
    arrow(CX + COL + 155, CY + ROWS[row], EXR - 117, CY - 60 + (ROWS[row] / 12))
arrow(CX + COL + 155, CY + ROWS[9], EXR - 117, CY + 220)

# notes
ny = 1770
out.append(f'<rect x="80" y="{ny - 40}" width="{W - 160}" height="170" rx="10" fill="#F1F8F3" stroke="{GREEN}" stroke-width="1.2"/>')
text(110, ny - 8, 'Enforced, not just drawn: ArchitectureSpec fails if a domain, application or port file imports an adapter, the HTTP layer, play.api.mvc, Anorm, JDBC or SQL helpers.', 16, 700, GREEN, 'start')
notes = ['Database outcomes are translated at the edge: EXCLUDE violations → SlotTakenError / VehicleBusyError (PgSchedulingStore), deadlocks → TransientStorageError (Db.tx). No SQL code reaches a use case.',
         'Cross-module needs are narrow ports owned by the consumer: booking sees ConsultationBookingPort (3 methods) and OwnedVehicles (1), the guard sees Authenticator (1).',
         'Kept on purpose (docs/solid-review.md): metrics and the request logger are cross-cutting; play-json input Reads sit with the use cases; ports take a DbSession so a use case',
         'can make several writes atomic; password hashing is a plain module; seed, migrations and /health run SQL directly because they are infrastructure tools.']
for i, n in enumerate(notes):
    text(110, ny + 24 + i * 30, n, 14, 400, INK, 'start')
out.append('</svg>')
open('hexagonal-architecture.svg', 'w').write('\n'.join(out))
