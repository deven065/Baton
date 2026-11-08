import { forwardRef, type ButtonHTMLAttributes } from 'react';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from '@/lib/utils';

const button = cva(
  'inline-flex items-center justify-center gap-2 rounded-lg font-medium whitespace-nowrap transition-colors disabled:pointer-events-none disabled:opacity-40 cursor-pointer',
  {
    variants: {
      kind: {
        primary: 'bg-brand-600 text-white hover:bg-brand-700',
        secondary: 'bg-card text-ink-900 border border-line-strong hover:bg-subtle',
        ghost: 'text-ink-700 hover:bg-subtle',
        danger: 'bg-conflict text-white hover:opacity-90',
        dark: 'bg-ink-900 text-white hover:bg-ink-700',
      },
      size: {
        md: 'px-3.5 py-2 text-small',
        lg: 'px-5 py-3 text-body',
      },
    },
    defaultVariants: { kind: 'secondary', size: 'md' },
  },
);

export type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & VariantProps<typeof button>;

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(({ className, kind, size, ...props }, ref) => (
  <button ref={ref} className={cn(button({ kind, size }), className)} {...props} />
));
Button.displayName = 'Button';
