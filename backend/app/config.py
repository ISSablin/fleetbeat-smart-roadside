from dataclasses import dataclass, field
from pathlib import Path
import os


def default_data_dir() -> Path:
    return Path(__file__).resolve().parent.parent / "data"


@dataclass(frozen=True)
class Settings:
    app_name: str = "FleetBeat Smart Roadside"
    data_dir: Path = field(default_factory=default_data_dir)

    @property
    def database_path(self) -> Path:
        return self.data_dir / "roadside.db"

    @classmethod
    def from_env(cls) -> "Settings":
        value = os.environ.get("ROADSIDE_DATA_DIR")
        data_dir = Path(value).expanduser().resolve() if value else default_data_dir()
        return cls(data_dir=data_dir)
