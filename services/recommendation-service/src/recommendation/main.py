import asyncio
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse

from recommendation.auth import AuthError, Caller, caller_from_header
from recommendation.consumer import run_consumer
from recommendation.rank import category, home, recently_viewed, related, trending, utcnow
from recommendation.settings import Settings, load_settings
from recommendation.store import MemoryStore, PostgresStore

log = logging.getLogger("recommendation")
Store = MemoryStore | PostgresStore


def create_app(store: Store, settings: Settings, consume: bool = False) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI):
        stop = asyncio.Event()
        task = None
        if consume and settings.kafka_brokers:
            task = asyncio.create_task(run_consumer(settings, store, stop))
        yield
        stop.set()
        if task is not None:
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                log.info("Recommendation consumer stopped")

    app = FastAPI(title="BAZAAR recommendation", lifespan=lifespan)
    app.state.store = store
    app.state.settings = settings

    @app.get("/health/live")
    def live():
        return {"status": "UP"}

    @app.get("/health/ready")
    def ready():
        if not store.ready():
            return JSONResponse({"status": "DOWN"}, status_code=503)
        return {"status": "UP"}

    @app.get("/api/v1/recommendations/home")
    def read_home(request: Request):
        caller = _optional(request)
        return home(store.state(), utcnow(), _limit(request), caller.subject if caller else None)

    @app.get("/api/v1/recommendations/trending")
    def read_trending(request: Request):
        _optional(request)
        return {"items": trending(store.state(), utcnow(), _limit(request))}

    @app.get("/api/v1/recommendations/products/{product_id}/related")
    def read_related(product_id: str, request: Request):
        _optional(request)
        _uuid(product_id, "productId")
        return {"productId": product_id, "items": related(store.state(), product_id, _limit(request))}

    @app.get("/api/v1/recommendations/categories/{category_id}")
    def read_category(category_id: str, request: Request):
        _optional(request)
        _uuid(category_id, "categoryId")
        return {"categoryId": category_id, "items": category(store.state(), category_id, _limit(request))}

    @app.get("/api/v1/recommendations/recently-viewed")
    def read_recent(request: Request):
        caller = _required(request)
        return {"items": recently_viewed(store.state(), caller.subject, _limit(request))}

    @app.post("/api/v1/recommendations/rebuild")
    def rebuild(request: Request):
        caller = _required(request)
        if not caller.admin():
            raise HTTPException(status_code=403, detail="Only an administrator can rebuild recommendations.")
        return {"events": store.rebuild()}

    return app


def _optional(request: Request) -> Caller | None:
    _reject_customer_query(request)
    try:
        return caller_from_header(request.headers.get("authorization"), request.app.state.settings)
    except AuthError as error:
        raise HTTPException(status_code=401, detail=str(error)) from error


def _required(request: Request) -> Caller:
    caller = _optional(request)
    if caller is None:
        raise HTTPException(status_code=401, detail="A bearer token is required.")
    return caller


def _reject_customer_query(request: Request) -> None:
    if "customerId" in request.query_params:
        raise HTTPException(status_code=400, detail="customerId is not accepted.")


def _limit(request: Request) -> int:
    raw = request.query_params.get("limit")
    if raw is None or raw == "":
        return 8
    if not raw.isdigit():
        raise HTTPException(status_code=400, detail="limit is invalid")
    number = int(raw)
    if number < 1 or number > 20:
        raise HTTPException(status_code=400, detail="limit is invalid")
    return number


def _uuid(value: str, name: str) -> None:
    from recommendation.model import UUID_RE

    if not UUID_RE.match(value):
        raise HTTPException(status_code=400, detail=f"{name} is invalid")


settings = load_settings()
store: Store = PostgresStore(settings.database_url) if settings.database_url else MemoryStore()
app = create_app(store, settings, consume=bool(settings.kafka_brokers))
