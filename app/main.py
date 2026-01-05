from config import LMUSIC_ROOT, GRADLEW, OUTPUT_APK_DIR, ensure_paths


def main() -> None:
    ensure_paths()
    print(f"LMusic project: {LMUSIC_ROOT}")
    print(f"Gradle wrapper: {GRADLEW}")
    print(f"Local output dir: {OUTPUT_APK_DIR}")


if __name__ == "__main__":
    main()
