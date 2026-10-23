from dataclasses import dataclass, field
from pathlib import Path
import os


def default_data_dir() -> Path:
    return Path(__file__).resolve().parent.parent / "data"


@dataclass(frozen=True)
class Settings:
    app_name: str = "FleetBeat Smart Roadside Assistant"
    jwt_issuer: str = "fleetbeat-smart-roadside"
    jwt_audience: str = "fleetbeat-mobile"
    token_ttl_seconds: int = 86400
    ai_gateway_url: str = ""
    ai_client_token: str = field(default="", repr=False)
    report_upload_enabled: bool = False
    report_upload_url: str = ""
    report_upload_method: str = "POST"
    report_upload_api_key: str = field(default="", repr=False)
    data_dir: Path = field(default_factory=default_data_dir)

    @property
    def database_path(self) -> Path:
        return self.data_dir / "roadside.db"

    @classmethod
    def from_env(cls) -> "Settings":
        value = os.environ.get("ROADSIDE_DATA_DIR")
        data_dir = Path(value).expanduser().resolve() if value else default_data_dir()
        return cls(report_upload_enabled=os.environ.get("ROADSIDE_REPORT_UPLOAD_ENABLED", "false").lower() == "true",
                   report_upload_url=os.environ.get("ROADSIDE_REPORT_UPLOAD_URL", "").strip(),
                   report_upload_method=os.environ.get("ROADSIDE_REPORT_UPLOAD_METHOD", "POST").strip().upper(),
                   report_upload_api_key=os.environ.get("ROADSIDE_REPORT_UPLOAD_API_KEY", "").strip(), data_dir=data_dir, ai_gateway_url=os.environ.get("ROADSIDE_AI_GATEWAY_URL", "").strip(),
                   ai_client_token=os.environ.get("ROADSIDE_AI_CLIENT_TOKEN", "").strip())
