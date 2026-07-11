import type { MouseEvent as ReactMouseEvent } from "react";

const nestedControlSelector = [
  "button",
  "a",
  "input",
  "select",
  "textarea",
  "[role='button']",
  "[data-track-context-ignore]",
].join(",");

export function isNestedTrackControl(
  currentTarget: EventTarget,
  closestControl: Element | null,
): boolean {
  return closestControl !== null && closestControl !== currentTarget;
}

/**
 * Opens the tag editor from a song surface while keeping nested action buttons inert.
 * The caller owns playback state; this helper never selects or starts a track.
 */
export function editTrackFromContextMenu<T>(
  event: ReactMouseEvent<HTMLElement>,
  track: T,
  onEdit: (track: T) => void,
): void {
  event.preventDefault();
  event.stopPropagation();

  const target = event.target;
  if (target instanceof Element) {
    const closestControl = target.closest(nestedControlSelector);
    if (isNestedTrackControl(event.currentTarget, closestControl)) return;
  }

  onEdit(track);
}
