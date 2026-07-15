type IconProps = { className?: string; size?: number };

const svg = (size: number, className?: string) => ({
  width: size,
  height: size,
  viewBox: "0 0 24 24",
  fill: "none",
  stroke: "currentColor",
  strokeWidth: 1.75,
  strokeLinecap: "round" as const,
  strokeLinejoin: "round" as const,
  className,
});

export const RadarIcon = ({ className, size = 20 }: IconProps) => (
  <svg {...svg(size, className)}>
    <path d="M19.07 4.93A10 10 0 0 0 6.99 3.34" />
    <path d="M4 6h.01" />
    <path d="M2.29 9.62A10 10 0 1 0 21.31 8.35" />
    <path d="M16.24 7.76A6 6 0 1 0 8.23 16.67" />
    <path d="M12 18h.01" />
    <path d="M17.99 11.66A6 6 0 0 1 15.77 16.67" />
    <circle cx="12" cy="12" r="2" />
    <path d="m13.41 10.59 5.66-5.66" />
  </svg>
);

export const UnlinkIcon = ({ className, size = 20 }: IconProps) => (
  <svg {...svg(size, className)}>
    <path d="m18.84 12.25 1.72-1.71a4.24 4.24 0 0 0-6-6l-1.71 1.72" />
    <path d="m5.17 11.75-1.71 1.71a4.24 4.24 0 0 0 6 6l1.71-1.71" />
    <path d="m2 2 20 20" />
  </svg>
);

export const RefreshIcon = ({ className, size = 16 }: IconProps) => (
  <svg {...svg(size, className)}>
    <path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8" />
    <path d="M21 3v5h-5" />
    <path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16" />
    <path d="M8 16H3v5" />
  </svg>
);

export const FileAudioIcon = ({ className, size = 18 }: IconProps) => (
  <svg {...svg(size, className)}>
    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
    <path d="M14 2v6h6" />
    <path d="M8 16a2 2 0 1 0 4 0V9l4 1.5" />
  </svg>
);

export const FileTextIcon = ({ className, size = 16 }: IconProps) => (
  <svg {...svg(size, className)}>
    <path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7z" />
    <path d="M14 2v5h5" />
    <path d="M10 9H8M16 13H8M16 17H8" />
  </svg>
);


export const CloseIcon = ({ className, size = 20 }: IconProps) => (
  <svg {...svg(size, className)}>
    <path d="M18 6 6 18M6 6l12 12" />
  </svg>
);

