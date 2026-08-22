import { Check, ChevronDown, LoaderCircle, X } from "lucide-react";
import {
  createContext,
  type ReactNode,
  useCallback,
  useContext,
  useEffect,
  useId,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { createPortal } from "react-dom";

export interface SelectMenuOption {
  value: string;
  label: string;
  detail?: string;
  disabled?: boolean;
}

interface SelectMenuProps {
  ariaLabel: string;
  className?: string;
  disabled?: boolean;
  onChange: (value: string) => void;
  options: readonly SelectMenuOption[];
  placeholder?: string;
  value: string;
}

interface ConfirmDialogProps {
  body: ReactNode;
  busy?: boolean;
  cancelLabel?: string;
  confirmLabel?: string;
  danger?: boolean;
  onCancel: () => void;
  onConfirm: () => void;
  open: boolean;
  title: string;
}

const SelectLayerContext = createContext({
  openSelectId: "",
  setOpenSelectId: (_value: string) => {},
});

export function SharedControlsProvider({ children }: { children: ReactNode }) {
  const [openSelectId, setOpenSelectId] = useState("");
  const value = useMemo(() => ({ openSelectId, setOpenSelectId }), [openSelectId]);
  return <SelectLayerContext.Provider value={value}>{children}</SelectLayerContext.Provider>;
}

function enabledIndex(options: readonly SelectMenuOption[], start: number, direction: number) {
  if (!options.length) return -1;
  for (let offset = 0; offset < options.length; offset += 1) {
    const index = (start + direction * offset + options.length) % options.length;
    if (!options[index]?.disabled) return index;
  }
  return -1;
}

export function SelectMenu({
  ariaLabel,
  className = "",
  disabled = false,
  onChange,
  options,
  placeholder = "请选择",
  value,
}: SelectMenuProps) {
  const selectId = useId();
  const listboxId = `${selectId}-listbox`;
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const optionRefs = useRef<Array<HTMLButtonElement | null>>([]);
  const { openSelectId, setOpenSelectId } = useContext(SelectLayerContext);
  const open = openSelectId === selectId;
  const [activeIndex, setActiveIndex] = useState(-1);
  const [openAbove, setOpenAbove] = useState(false);
  const selectedIndex = options.findIndex((option) => option.value === value);
  const selectedOption = selectedIndex >= 0 ? options[selectedIndex] : null;

  const closeMenu = useCallback((restoreFocus = false) => {
    setOpenSelectId("");
    if (restoreFocus) window.requestAnimationFrame(() => triggerRef.current?.focus());
  }, [setOpenSelectId]);

  const openMenu = () => {
    if (disabled || !options.length) return;
    const bounds = triggerRef.current?.getBoundingClientRect();
    const estimatedHeight = Math.min(options.length, 6) * 46 + 14;
    setOpenAbove(Boolean(
      bounds
      && bounds.bottom + estimatedHeight > window.innerHeight - 12
      && bounds.top > estimatedHeight,
    ));
    setActiveIndex(
      selectedIndex >= 0 && !options[selectedIndex]?.disabled
        ? selectedIndex
        : enabledIndex(options, 0, 1),
    );
    setOpenSelectId(selectId);
  };

  const choose = (option: SelectMenuOption) => {
    if (option.disabled) return;
    onChange(option.value);
    closeMenu(true);
  };

  const moveActive = (direction: number) => {
    const start = activeIndex < 0
      ? direction > 0 ? 0 : options.length - 1
      : activeIndex + direction;
    const nextIndex = enabledIndex(options, start, direction);
    if (nextIndex < 0) return;
    setActiveIndex(nextIndex);
    window.requestAnimationFrame(() => optionRefs.current[nextIndex]?.focus());
  };

  useLayoutEffect(() => {
    if (!open || activeIndex < 0) return;
    optionRefs.current[activeIndex]?.focus();
  }, [activeIndex, open]);

  useEffect(() => {
    if (!open) return undefined;
    const closeFromOutside = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) closeMenu(false);
    };
    const closeFromViewportChange = () => closeMenu(false);
    document.addEventListener("pointerdown", closeFromOutside, true);
    window.addEventListener("resize", closeFromViewportChange);
    return () => {
      document.removeEventListener("pointerdown", closeFromOutside, true);
      window.removeEventListener("resize", closeFromViewportChange);
    };
  }, [closeMenu, open]);

  useEffect(() => {
    if (disabled && open) closeMenu(false);
  }, [closeMenu, disabled, open]);

  return (
    <div
      className={`ui-select ${open ? "is-open" : ""} ${disabled ? "is-disabled" : ""} ${className}`}
      data-no-drag
      ref={rootRef}
    >
      <button
        aria-controls={open ? listboxId : undefined}
        aria-expanded={open}
        aria-haspopup="listbox"
        aria-label={ariaLabel}
        className="ui-select__trigger"
        disabled={disabled}
        onClick={() => (open ? closeMenu(false) : openMenu())}
        onKeyDown={(event) => {
          if (["ArrowDown", "ArrowUp", "Enter", " "].includes(event.key)) {
            event.preventDefault();
            if (!open) openMenu();
            else if (event.key === "ArrowDown") moveActive(1);
            else if (event.key === "ArrowUp") moveActive(-1);
          }
        }}
        ref={triggerRef}
        type="button"
      >
        <span className={`ui-select__value ${selectedOption ? "" : "is-placeholder"}`}>
          <strong>{selectedOption?.label ?? placeholder}</strong>
          {selectedOption?.detail ? <small>{selectedOption.detail}</small> : null}
        </span>
        <ChevronDown aria-hidden="true" className="ui-select__chevron" size={16} />
      </button>

      {open ? (
        <div
          aria-label={ariaLabel}
          className={`ui-select__menu ${openAbove ? "opens-above" : ""}`}
          id={listboxId}
          onKeyDown={(event) => {
            if (event.key === "Escape") {
              event.preventDefault();
              closeMenu(true);
            } else if (event.key === "ArrowDown") {
              event.preventDefault();
              moveActive(1);
            } else if (event.key === "ArrowUp") {
              event.preventDefault();
              moveActive(-1);
            } else if (event.key === "Home") {
              event.preventDefault();
              setActiveIndex(enabledIndex(options, 0, 1));
            } else if (event.key === "End") {
              event.preventDefault();
              setActiveIndex(enabledIndex(options, options.length - 1, -1));
            }
          }}
          role="listbox"
        >
          {options.map((option, index) => (
            <button
              aria-selected={option.value === value}
              className={`ui-select__option ${index === activeIndex ? "is-active" : ""} ${option.value === value ? "is-selected" : ""}`}
              disabled={option.disabled}
              key={option.value}
              onClick={() => choose(option)}
              onFocus={() => setActiveIndex(index)}
              ref={(element) => {
                optionRefs.current[index] = element;
              }}
              role="option"
              tabIndex={index === activeIndex ? 0 : -1}
              type="button"
            >
              <span>
                <strong>{option.label}</strong>
                {option.detail ? <small>{option.detail}</small> : null}
              </span>
              {option.value === value ? <Check aria-hidden="true" size={15} /> : null}
            </button>
          ))}
        </div>
      ) : null}
    </div>
  );
}

export function ConfirmDialog({
  body,
  busy = false,
  cancelLabel = "取消",
  confirmLabel = "确认",
  danger = false,
  onCancel,
  onConfirm,
  open,
  title,
}: ConfirmDialogProps) {
  const titleId = useId();
  const cancelRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return undefined;
    window.requestAnimationFrame(() => cancelRef.current?.focus());
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !busy) onCancel();
    };
    document.addEventListener("keydown", closeOnEscape);
    return () => document.removeEventListener("keydown", closeOnEscape);
  }, [busy, onCancel, open]);

  if (!open) return null;
  const portalTarget = document.querySelector(".window-surface") ?? document.body;
  return createPortal(
    <div
      className="ui-confirm-backdrop"
      data-no-drag
      onMouseDown={(event) => {
        event.stopPropagation();
        if (event.target === event.currentTarget && !busy) onCancel();
      }}
    >
      <section
        aria-labelledby={titleId}
        aria-modal="true"
        className="ui-confirm-dialog"
        role="alertdialog"
      >
        <header>
          <div>
            <span>请确认</span>
            <h2 id={titleId}>{title}</h2>
          </div>
          <button aria-label="关闭" disabled={busy} onClick={onCancel} type="button">
            <X aria-hidden="true" size={18} />
          </button>
        </header>
        <div className="ui-confirm-dialog__body">{body}</div>
        <footer>
          <button
            className="ui-confirm-dialog__cancel"
            disabled={busy}
            onClick={onCancel}
            ref={cancelRef}
            type="button"
          >
            {cancelLabel}
          </button>
          <button
            className={`ui-confirm-dialog__confirm ${danger ? "is-danger" : ""}`}
            disabled={busy}
            onClick={onConfirm}
            type="button"
          >
            {busy ? <LoaderCircle aria-hidden="true" className="ui-spin" size={15} /> : null}
            {busy ? "处理中…" : confirmLabel}
          </button>
        </footer>
      </section>
    </div>,
    portalTarget,
  );
}
