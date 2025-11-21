import pathlib
import sys

import uvicorn


def main() -> None:
    """
    Run the FastAPI app for local development.

    You can run this from the `backend` directory:
        python run_dev.py
    """

    # Ensure the `backend` directory is on sys.path so `app.main` can be imported.
    current_dir = pathlib.Path(__file__).resolve().parent
    if str(current_dir) not in sys.path:
        sys.path.insert(0, str(current_dir))

    uvicorn.run(
        "app.main:app",
        host="0.0.0.0",
        port=8000,
        reload=True,
    )


if __name__ == "__main__":
    main()



