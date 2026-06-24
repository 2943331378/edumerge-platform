# EduMerge Design System

## Color Tokens

### Semantic Colors (Tailwind CSS variables via shadcn/ui)

| Token | Light | Dark | Usage |
|-------|-------|------|-------|
| `--background` | white | slate-950 | Page background |
| `--foreground` | slate-950 | slate-50 | Primary text |
| `--card` | white | slate-950 | Card surfaces |
| `--primary` | indigo-600 | indigo-400 | CTA, active states, links |
| `--muted` | slate-100 | slate-800 | Secondary backgrounds |
| `--muted-foreground` | slate-500 | slate-400 | Secondary text |
| `--destructive` | red-500 | red-400 | Errors, delete actions |
| `--border` | slate-200 | slate-800 | Default borders |

### File Type Colors

| Type | Color | Tailwind |
|------|-------|----------|
| PDF | Red | `text-red-400` |
| Excel/CSV | Emerald | `text-emerald-400` |
| PPT | Orange | `text-orange-400` |
| Markdown | Sky | `text-sky-400` |
| Other | Muted | `text-muted-foreground/60` |

### Folder Colors (predefined palette)

```
#6366f1  indigo    #f43f5e  rose      #10b981  emerald
#f59e0b  amber     #3b82f6  blue      #8b5cf6  violet
#ec4899  pink      #14b8a6  teal      #f97316  orange
#64748b  slate
```

### Status Colors

| Status | Color | Animation |
|--------|-------|-----------|
| Done | `emerald-400` + glow shadow | None |
| Processing | `blue-400` | `animate-pulse` |
| Uploading | `amber-400` | `animate-pulse` |
| Error | `destructive` | None |

## Typography

| Element | Size | Weight | Notes |
|---------|------|--------|-------|
| Logo text | 15px | Bold | `text-[15px] font-bold` |
| Doc name | 12px | Normal/Medium | `text-xs`, active: `font-medium` |
| Folder name | 12px | Medium | `text-xs font-medium` |
| Search input | 11px | Normal | `text-[11px]` |
| Secondary info | 11px | Normal | `text-[11px]`, contrast `/60` |
| Tertiary info | 11px | Normal | `text-[11px]`, contrast `/50` |

**Minimum contrast ratios:**
- Primary text: 4.5:1 (WCAG AA)
- Secondary info (`/60`): ~3:1 minimum
- Tertiary info (`/50`): decorative only, not essential

## Spacing Scale

| Context | Value | Tailwind |
|---------|-------|----------|
| Sidebar width (expanded) | 260px | `w-[260px]` |
| Sidebar width (collapsed) | 64px | `w-[64px]` |
| Item horizontal padding | 12px | `px-3` |
| Item vertical padding | 4px (desktop) / 8px (mobile) | `py-1 max-md:py-2` |
| Section gap | 2px | `space-y-0.5` |
| Icon-to-text gap | 8px | `gap-2` |

## Border Radius

| Element | Radius | Tailwind |
|---------|--------|----------|
| List items | 8px | `rounded-lg` |
| Upload area | 12px | `rounded-xl` |
| Logo container | 16px | `rounded-2xl` |
| Color dots | Full | `rounded-full` |
| Buttons | 8px | `rounded-lg` |

## Touch Targets

- Minimum: 44px x 44px (`min-w-[44px] min-h-[44px]`)
- Applies to all interactive elements: buttons, drag handles, folder headers

## Glass Morphism (Sidebar)

```css
bg-white/70 dark:bg-slate-900/80  /* semi-transparent surface */
backdrop-blur-xl                   /* blur effect */
border-r border-border             /* subtle edge */
```

## Animation

| Pattern | Usage |
|---------|-------|
| `animate-pulse` | Processing/uploading status |
| `transition-all duration-200` | Folder expand/collapse |
| `transition-all duration-300` | Sidebar collapse/expand |
| `hover:scale-110` | Color picker dots |
| Chevron rotation (0→90deg) | Folder expand indicator |

## Shadows

| Element | Shadow |
|---------|--------|
| Status dot (done) | `shadow-[0_0_6px] shadow-emerald-400/30` |
| Collapse button | `shadow-lg` |
| Drag ghost | `shadow-lg` |
| Mobile bottom sheet | `shadow-2xl` |

## Responsive Breakpoints

- Mobile: `< 768px` (`max-md:`)
- Desktop: `≥ 768px` (`md:`)

### Mobile-Specific Patterns
- Sidebar: overlay with backdrop, swipe-left to close
- Document actions: always visible (no hover)
- Touch targets: 44px minimum
- Bottom sheet: `max-h-[60vh]`, `rounded-t-2xl`
