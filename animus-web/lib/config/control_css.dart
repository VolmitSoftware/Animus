/// The editor's one control system, emitted after the Ascii stylesheet so it
/// wins by order. arcane_jaspr sets button and field heights inline, so the
/// size rules carry `!important`; nothing else does.
///
/// Shape: every corner is `--animus-radius` (0). No inset or offset shadow,
/// no one-sided accent border, no accent gradient. Selection is a raised grey
/// fill with a full 1 px pink border; focus is a 2 px pink outline.
///
/// Buttons: primary, secondary (also `outline`), ghost, destructive, each in
/// small (`sm`, `iconSm`) and default (`md`, `lg`, `iconMd`, `iconLg`). The
/// icon sizes are square. Native buttons join by class: `.animus-tab` and
/// `.animus-menu-item` are default-height ghost buttons, `.animus-skip` a
/// secondary one, `.animus-rig-lane-label` a small ghost one.
///
/// | size    | height | padding  | font      | icon    |
/// |---------|--------|----------|-----------|---------|
/// | small   | 28 px  | 0 0.625r | 13 px     | 14 px   |
/// | default | 36 px  | 0 0.875r | 14 px     | 16 px   |
/// | phone   | 44 px  | as above | as above  | as above|
///
/// Primary and destructive are weight 600, the rest 400; every button has a
/// 1 px border (transparent where the fill carries the edge) so all
/// variants of a size measure the same. Text inputs and selects take the
/// default height, so a field and a button in one row line up.
const String controlCss = r'''
#arcane-root {
  --radius: var(--animus-radius);
  --radius-xs: var(--animus-radius);
  --radius-sm: var(--animus-radius);
  --radius-md: var(--animus-radius);
  --radius-lg: var(--animus-radius);
  --radius-xl: var(--animus-radius);
  --shadcn-item-radius: var(--animus-radius);
  --shadow-xs: none;
  --shadow-sm: none;
  --shadow-md: none;
  --shadow-lg: none;
  --shadow-xl: none;
  --shadcn-focus-ring: none;
  --shadcn-control-shadow: none;
  --animus-control-h: 2.25rem;
  --animus-control-h-sm: 1.75rem;
  --animus-control-pad: 0.875rem;
  --animus-control-pad-sm: 0.625rem;
  --animus-control-font: 0.875rem;
  --animus-control-font-sm: 0.8125rem;
  --animus-control-icon: 1rem;
  --animus-control-icon-sm: 0.875rem;
  --animus-control-border: 1px;
}
@media (max-width: 760px), (pointer: coarse) {
  #arcane-root {
    --animus-control-h: 2.75rem;
    --animus-control-h-sm: 2.75rem;
  }
}
#arcane-root :where(*, *::before, *::after) {
  border-radius: var(--animus-radius);
}
#arcane-root.arcane-theme-ascii {
  --font-control: var(--font-sans);
}
#arcane-root.arcane-theme-ascii :is(button, input, select, .arcane-button, .arcane-cycle-button, .arcane-badge, .arcane-status-badge, .arcane-data-table th, .arcane-static-table th, .ascii-label) {
  font-family: var(--font-sans);
}
#arcane-root.arcane-theme-ascii :is(textarea, code, kbd, pre, samp) {
  font-family: var(--font-mono);
}
#arcane-root.arcane-theme-ascii :is(button, summary, label[for], select, [role='tab'], [role='menuitem'], [role='option'], [role='radio'], [role='switch'], input[type='checkbox'], input[type='radio'], input[type='range']):not(:disabled):not([aria-disabled='true']) {
  cursor: pointer;
}
#arcane-root.arcane-theme-ascii :is(button, select, input):disabled,
#arcane-root.arcane-theme-ascii [aria-disabled='true'] {
  cursor: not-allowed;
}

/* Button geometry. */
#arcane-root.arcane-theme-ascii :is(.arcane-button, .arcane-cycle-button) {
  --animus-btn-bg: var(--secondary);
  --animus-btn-fg: var(--secondary-foreground);
  --animus-btn-border: var(--border);
  --animus-btn-hover-bg: var(--accent);
  --animus-btn-hover-border: var(--input);
  --animus-btn-active-bg: var(--card-hover);
  box-sizing: border-box;
  height: var(--animus-control-h) !important;
  min-height: var(--animus-control-h) !important;
  padding: 0 var(--animus-control-pad) !important;
  gap: 0.5rem !important;
  font-size: var(--animus-control-font) !important;
  font-weight: 400;
  line-height: 1.25rem;
  letter-spacing: 0;
  border: var(--animus-control-border) solid var(--animus-btn-border) !important;
  background: var(--animus-btn-bg) !important;
  color: var(--animus-btn-fg) !important;
  box-shadow: none !important;
  filter: none !important;
  opacity: 1 !important;
}
#arcane-root.arcane-theme-ascii :is(.arcane-button, .arcane-cycle-button):is([data-size='sm'], [data-size='iconSm']) {
  height: var(--animus-control-h-sm) !important;
  min-height: var(--animus-control-h-sm) !important;
  padding: 0 var(--animus-control-pad-sm) !important;
  gap: 0.375rem !important;
  font-size: var(--animus-control-font-sm) !important;
}
#arcane-root.arcane-theme-ascii .arcane-button[data-size^='icon'] {
  width: var(--animus-control-h) !important;
  min-width: var(--animus-control-h) !important;
  padding: 0 !important;
}
#arcane-root.arcane-theme-ascii .arcane-button[data-size='iconSm'] {
  width: var(--animus-control-h-sm) !important;
  min-width: var(--animus-control-h-sm) !important;
}
#arcane-root.arcane-theme-ascii :is(.arcane-button, .arcane-cycle-button) :is(svg, .arcane-icon) {
  width: var(--animus-control-icon);
  height: var(--animus-control-icon);
  font-size: var(--animus-control-icon);
}
#arcane-root.arcane-theme-ascii :is(.arcane-button, .arcane-cycle-button):is([data-size='sm'], [data-size='iconSm']) :is(svg, .arcane-icon) {
  width: var(--animus-control-icon-sm);
  height: var(--animus-control-icon-sm);
  font-size: var(--animus-control-icon-sm);
}

/* Button colours: rest, hover, active and pressed per variant. */
#arcane-root.arcane-theme-ascii :is(.arcane-button, .arcane-cycle-button)[data-variant='primary'] {
  --animus-btn-bg: var(--primary);
  --animus-btn-fg: var(--primary-foreground);
  --animus-btn-border: var(--primary);
  --animus-btn-hover-bg: var(--animus-pink-deep);
  --animus-btn-hover-border: var(--animus-pink-deep);
  --animus-btn-active-bg: color-mix(in srgb, var(--animus-pink-deep) 82%, #000);
  font-weight: 600;
}
#arcane-root.arcane-theme-ascii :is(.arcane-button, .arcane-cycle-button)[data-variant='ghost'],
#arcane-root.arcane-theme-ascii .arcane-button[data-variant='link'] {
  --animus-btn-bg: transparent;
  --animus-btn-fg: var(--foreground);
  --animus-btn-border: transparent;
  --animus-btn-hover-bg: var(--secondary);
  --animus-btn-hover-border: transparent;
  --animus-btn-active-bg: var(--accent);
}
#arcane-root.arcane-theme-ascii :is(.arcane-button, .arcane-cycle-button)[data-variant='destructive'] {
  --animus-btn-bg: var(--animus-error);
  --animus-btn-fg: var(--animus-error-foreground);
  --animus-btn-border: var(--animus-error);
  --animus-btn-hover-bg: color-mix(in srgb, var(--animus-error) 86%, #000);
  --animus-btn-hover-border: color-mix(in srgb, var(--animus-error) 86%, #000);
  --animus-btn-active-bg: color-mix(in srgb, var(--animus-error) 74%, #000);
  font-weight: 600;
}
#arcane-root.arcane-theme-ascii :is(.arcane-button, .arcane-cycle-button):hover:not(:disabled):not([data-disabled='true']) {
  background: var(--animus-btn-hover-bg) !important;
  border-color: var(--animus-btn-hover-border) !important;
}
#arcane-root.arcane-theme-ascii :is(.arcane-button, .arcane-cycle-button):active:not(:disabled):not([data-disabled='true']) {
  background: var(--animus-btn-active-bg) !important;
}
#arcane-root.arcane-theme-ascii :is(.arcane-button, .arcane-cycle-button):is([aria-pressed='true'], [aria-checked='true'], [aria-selected='true'], [aria-current='page']):not([data-variant='primary']):not([data-variant='destructive']) {
  background: var(--card-hover) !important;
  color: var(--foreground) !important;
  border-color: var(--primary) !important;
}
#arcane-root.arcane-theme-ascii :is(.arcane-button, .arcane-cycle-button):is(:disabled, [data-disabled='true'], [aria-disabled='true']) {
  background: var(--muted) !important;
  color: var(--muted-foreground) !important;
  border-color: var(--border) !important;
}
#arcane-root.arcane-theme-ascii :is(.arcane-button, .arcane-cycle-button):is([data-variant='ghost'], [data-variant='link']):is(:disabled, [data-disabled='true'], [aria-disabled='true']) {
  background: transparent !important;
  border-color: transparent !important;
}

/* Native buttons in the same system. */
#arcane-root.arcane-theme-ascii :is(.animus-tab, .animus-menu-item, .animus-skip, .animus-rig-lane-label) {
  box-sizing: border-box;
  min-height: var(--animus-control-h);
  font-size: var(--animus-control-font);
  border: var(--animus-control-border) solid transparent;
  box-shadow: none;
}
#arcane-root.arcane-theme-ascii .animus-skip {
  border-color: var(--border);
}
/* The toast's Dismiss is a small square icon button. */
#arcane-root.arcane-theme-ascii .arcane-toast-close {
  width: var(--animus-control-h-sm) !important;
  height: var(--animus-control-h-sm) !important;
  border: var(--animus-control-border) solid var(--border) !important;
  background: var(--secondary) !important;
  color: var(--secondary-foreground) !important;
}
#arcane-root.arcane-theme-ascii .arcane-toast-close:hover {
  background: var(--accent) !important;
  border-color: var(--input) !important;
}
#arcane-root.arcane-theme-ascii .animus-rig-lane-label {
  min-height: var(--animus-control-h-sm);
  font-size: var(--animus-control-font-sm);
}

/* Selection: a raised grey fill and a full pink border. Solid pink is for
   the one primary action. */
#arcane-root.arcane-theme-ascii :is(.animus-tabs, .animus-segmented) :is([aria-selected='true'], [aria-pressed='true'], [aria-checked='true']),
#arcane-root.arcane-theme-ascii :is(.animus-tab, .animus-rig-lane-label):is([aria-selected='true'], [aria-pressed='true']) {
  background: var(--card-hover) !important;
  color: var(--foreground) !important;
  border-color: var(--primary) !important;
  box-shadow: none !important;
}

/* Fields: the default control height, a full border, a pink border and
   outline on focus. */
#arcane-root.arcane-theme-ascii :is(.arcane-text-input:not([data-arcane-field-inner]), .arcane-text-input-container, select.arcane-select, .arcane-select-trigger) {
  box-sizing: border-box;
  height: var(--animus-control-h) !important;
  min-height: var(--animus-control-h) !important;
  font-size: var(--animus-control-font);
  box-shadow: none !important;
}
#arcane-root.arcane-theme-ascii .arcane-text-input[data-arcane-field-inner] {
  height: calc(var(--animus-control-h) - 2px) !important;
  min-height: 0 !important;
}
#arcane-root.arcane-theme-ascii :is(.arcane-text-input, .arcane-text-input-container, .arcane-textarea, .arcane-select, .arcane-select-trigger, input, select, textarea):is(:focus, :focus-visible, :focus-within) {
  box-shadow: none !important;
}
#arcane-root.arcane-theme-ascii :is(.arcane-text-input-container:focus-within, input:focus-visible, select:focus-visible, textarea:focus-visible) {
  border-color: var(--ring) !important;
}

/* Checkboxes, radios and ranges are square. */
#arcane-root.arcane-theme-ascii :is(input[type='checkbox'], input[type='radio']) {
  appearance: none;
  -webkit-appearance: none;
  box-sizing: border-box;
  display: inline-grid;
  place-content: center;
  flex: none;
  width: 1.125rem;
  height: 1.125rem;
  margin: 0;
  border: 1px solid var(--input);
  background: var(--card);
}
#arcane-root.arcane-theme-ascii :is(input[type='checkbox'], input[type='radio']):checked {
  border-color: var(--primary);
  background: var(--primary);
}
#arcane-root.arcane-theme-ascii input[type='checkbox']:checked::before {
  content: '';
  width: 0.375rem;
  height: 0.625rem;
  margin-top: -0.125rem;
  border: solid var(--primary-foreground);
  border-width: 0 2px 2px 0;
  transform: rotate(45deg);
}
#arcane-root.arcane-theme-ascii input[type='radio']:checked::before {
  content: '';
  width: 0.5rem;
  height: 0.5rem;
  background: var(--primary-foreground);
}
#arcane-root.arcane-theme-ascii :is(input[type='checkbox'], input[type='radio']):disabled {
  border-color: var(--border);
  background: var(--muted);
}
#arcane-root.arcane-theme-ascii input[type='range'] {
  appearance: none;
  -webkit-appearance: none;
  height: var(--animus-control-h-sm);
  background: transparent;
}
#arcane-root.arcane-theme-ascii input[type='range']::-webkit-slider-runnable-track {
  height: 4px;
  background: var(--border);
}
#arcane-root.arcane-theme-ascii input[type='range']::-moz-range-track {
  height: 4px;
  background: var(--border);
}
#arcane-root.arcane-theme-ascii input[type='range']::-webkit-slider-thumb {
  -webkit-appearance: none;
  width: 1rem;
  height: 1rem;
  margin-top: -6px;
  border: 1px solid var(--primary);
  border-radius: 0;
  background: var(--primary);
}
#arcane-root.arcane-theme-ascii input[type='range']::-moz-range-thumb {
  width: 1rem;
  height: 1rem;
  border: 1px solid var(--primary);
  border-radius: 0;
  background: var(--primary);
}

/* Floating surfaces sit on a full border, never a drop shadow. */
#arcane-root.arcane-theme-ascii :is(.arcane-toast, .arcane-dialog, .arcane-dialog-content, .arcane-popover, .arcane-dropdown-content, .arcane-select-content) {
  box-shadow: none !important;
  border: 1px solid var(--input) !important;
}

#arcane-root.arcane-theme-ascii a:not([class]) {
  color: var(--primary);
  text-underline-offset: 0.2em;
}
#arcane-root.arcane-theme-ascii a:not([class]):hover {
  color: var(--animus-pink-deep);
}
#arcane-root.arcane-theme-ascii :is(.animus-sidebar, .animus-menu, .animus-palette, .animus-tabs, .animus-segmented, .animus-inset-focus) :is(button, a, input, textarea, select, summary, [tabindex]):focus-visible {
  outline-offset: -2px;
}
''';
