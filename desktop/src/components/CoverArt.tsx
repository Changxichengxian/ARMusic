import type { CSSProperties } from "react";
import { coverMark, paletteFor, stableNumber } from "../lib/music";
import type { Track } from "../types";
import { useI18n } from "../i18n";

interface CoverArtProps {
  track: Track;
  className?: string;
  decorative?: boolean;
}

type CoverStyle = CSSProperties & {
  "--cover-a": string;
  "--cover-b": string;
  "--cover-c": string;
  "--cover-turn": string;
  "--cover-shift": string;
};

export function CoverArt({ track, className = "", decorative = false }: CoverArtProps) {
  const { t } = useI18n();
  const [a, b, c] = paletteFor(track);
  const seed = stableNumber(track.syncId);
  const hasCover = Boolean(track.coverUrl);
  const style: CoverStyle = {
    "--cover-a": a,
    "--cover-b": b,
    "--cover-c": c,
    "--cover-turn": `${seed % 32 - 16}deg`,
    "--cover-shift": `${18 + (seed % 34)}%`,
  };

  return (
    <div
      className={`cover-art ${hasCover ? "cover-art--image" : `cover-art--${seed % 4}`} ${className}`}
      style={style}
      role={decorative ? undefined : "img"}
      aria-label={decorative ? undefined : t("common.cover", { title: track.title })}
      aria-hidden={decorative || undefined}
    >
      {hasCover ? <img src={track.coverUrl} alt="" loading={decorative ? "lazy" : "eager"} decoding="async" draggable={false} /> : null}
      {!hasCover ? (
        <>
          <span className="cover-art__orb" />
          <span className="cover-art__line" />
          <span className="cover-art__edition">AR / {track.year || "MUSIC"}</span>
          <strong className="cover-art__mark">{coverMark(track.title)}</strong>
          <span className="cover-art__caption">{track.artist}</span>
        </>
      ) : null}
    </div>
  );
}
