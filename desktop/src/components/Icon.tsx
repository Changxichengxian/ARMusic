type IconName =
  | "download"
  | "folder"
  | "library"
  | "pause"
  | "phone"
  | "play"
  | "refresh"
  | "search"
  | "server"
  | "shield"
  | "upload"
  | "success";

interface IconProps {
  name: IconName;
  size?: number;
}

const paths: Record<IconName, string[]> = {
  download: ["M12 3v12", "m7 10 5 5 5-5", "M4 21h16"],
  folder: ["M3 7h6l2 2h10v10H3z", "M3 7v12"],
  library: ["M5 4v16", "M10 4v16", "M15 5l4 14"],
  pause: ["M8 5v14", "M16 5v14"],
  phone: ["M8 3h8a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M11 18h2"],
  play: ["M8 5v14l11-7z"],
  refresh: ["M20 7v5h-5", "M4 17v-5h5", "M6 9a7 7 0 0 1 11-2l3 3", "M18 15a7 7 0 0 1-11 2l-3-3"],
  search: ["M10.5 18a7.5 7.5 0 1 1 0-15 7.5 7.5 0 0 1 0 15z", "M16 16l5 5"],
  server: ["M4 5h16v6H4z", "M4 13h16v6H4z", "M8 8h.01", "M8 16h.01"],
  shield: ["M12 3 20 6v6c0 5-3.4 8-8 9-4.6-1-8-4-8-9V6z", "m8.5 12 2.2 2.2L15.5 9"],
  upload: ["M12 21V9", "m7 14 5-5 5 5", "M4 3h16"],
  success: ["M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20z", "m8 12 2.5 2.5L16 9"],
};

export function Icon({ name, size = 18 }: IconProps) {
  return (
    <svg
      aria-hidden="true"
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      {paths[name].map((path, index) => (
        <path d={path} key={`${name}-${index}`} />
      ))}
    </svg>
  );
}
