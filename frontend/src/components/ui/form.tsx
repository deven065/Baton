import { Check, Eye, EyeOff } from 'lucide-react';
import { forwardRef, useId, useState, type InputHTMLAttributes, type ReactNode, type TextareaHTMLAttributes } from 'react';
import { cn } from '@/lib/utils';

/** A text box. A password box gets an eye button at its right edge to show or hide what was typed. */
export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement> & { invalid?: boolean }>(
  ({ className, invalid, type, ...props }, ref) => {
    const [shown, setShown] = useState(false);
    const secret = type === 'password';
    const box = (
      <input
        ref={ref}
        type={secret && shown ? 'text' : type}
        className={cn(
          'w-full rounded-lg border bg-card px-3 py-2.5 text-body text-ink-900 placeholder:text-ink-400 outline-none transition-colors',
          invalid ? 'border-conflict' : 'border-line-strong focus:border-brand-600 focus:ring-3 focus:ring-brand-100',
          secret && 'pr-11',
          className,
        )}
        {...props}
      />
    );
    if (!secret) return box;
    return (
      <div className="relative">
        {box}
        <button
          type="button" tabIndex={0} aria-label={shown ? 'Hide password' : 'Show password'} aria-pressed={shown} title={shown ? 'Hide password' : 'Show password'}
          onClick={() => setShown(s => !s)}
          className="absolute inset-y-0 right-0 grid w-11 place-items-center rounded-r-lg text-ink-500 hover:text-ink-900 cursor-pointer"
        >
          {shown ? <EyeOff className="size-[18px]" aria-hidden /> : <Eye className="size-[18px]" aria-hidden />}
        </button>
      </div>
    );
  },
);
Input.displayName = 'Input';

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaHTMLAttributes<HTMLTextAreaElement>>(({ className, ...props }, ref) => (
  <textarea
    ref={ref}
    className={cn(
      'w-full rounded-lg border border-line-strong bg-card px-3.5 py-3 text-body text-ink-900 placeholder:text-ink-400 outline-none focus:border-brand-600 focus:ring-3 focus:ring-brand-100 resize-y',
      className,
    )}
    {...props}
  />
));
Textarea.displayName = 'Textarea';

/** Label + control, wired together with an id. */
export function Field({ label, hint, children }: { label: string; hint?: ReactNode; children: (id: string) => ReactNode }) {
  const id = useId();
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-caption font-medium text-ink-700">{label}</label>
      {children(id)}
      {hint}
    </div>
  );
}

export function Checkbox({ checked, onChange, label, id, className }: { checked: boolean; onChange?: (v: boolean) => void; label?: ReactNode; id?: string; className?: string }) {
  return (
    <label className={cn('inline-flex items-start gap-2.5 cursor-pointer select-none', className)}>
      <input id={id} type="checkbox" className="peer sr-only" checked={checked} onChange={e => onChange?.(e.target.checked)} />
      <span
        aria-hidden
        className={cn(
          'mt-px grid size-4 shrink-0 place-items-center rounded border-[1.5px] transition-[background-color,border-color,scale] duration-200 ease-out active:scale-90 peer-focus-visible:ring-2 peer-focus-visible:ring-brand-600',
          checked ? 'bg-brand-600 border-brand-600' : 'bg-card border-line-strong',
        )}
      >
        {checked && <Check className="check-draw size-3 text-white" strokeWidth={3} />}
      </span>
      {label && <span className="text-small text-ink-700">{label}</span>}
    </label>
  );
}

export function Radio({ checked, onChange, name, children }: { checked: boolean; onChange: () => void; name: string; children: ReactNode }) {
  return (
    <label
      className={cn(
        'flex gap-3 rounded-xl border p-3.5 cursor-pointer transition-colors',
        checked ? 'border-brand-600 bg-brand-50 ring-1 ring-brand-600' : 'border-line bg-card hover:bg-canvas',
      )}
    >
      <input type="radio" name={name} checked={checked} onChange={onChange} className="peer sr-only" />
      <span
        aria-hidden
        className={cn('mt-0.5 size-[18px] shrink-0 rounded-full bg-card transition-[border-width,border-color] duration-200 ease-out peer-focus-visible:ring-2 peer-focus-visible:ring-brand-600', checked ? 'border-[5px] border-brand-600' : 'border-[1.5px] border-line-strong')}
      />
      <span className="flex flex-col gap-0.5">{children}</span>
    </label>
  );
}

/** Segmented control (Day 1 / Week 1 / Month 1, sensitivity picker). The white thumb slides to the choice. */
export function Segmented<T extends string>({ options, value, onChange, label }: { options: readonly T[]; value: T; onChange: (v: T) => void; label: string }) {
  const i = Math.max(0, options.indexOf(value));
  return (
    <div role="radiogroup" aria-label={label} className="relative flex rounded-lg bg-subtle p-[3px]">
      <span
        aria-hidden
        className="absolute top-[3px] bottom-[3px] rounded-md bg-card shadow-card transition-[left] duration-300 ease-[cubic-bezier(0.22,1,0.36,1)]"
        style={{ left: `calc(3px + (100% - 6px) * ${i} / ${options.length})`, width: `calc((100% - 6px) / ${options.length})` }}
      />
      {options.map(o => (
        <button
          key={o}
          type="button"
          role="radio"
          aria-checked={o === value}
          onClick={() => onChange(o)}
          className={cn('relative z-10 flex-1 rounded-md px-3 py-1.5 text-small font-medium cursor-pointer transition-colors duration-200', o === value ? 'text-ink-900' : 'text-ink-500 hover:text-ink-900')}
        >
          {o}
        </button>
      ))}
    </div>
  );
}
