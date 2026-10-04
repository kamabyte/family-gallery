import InputError from '@/components/input-error';
import { Label } from '@/components/ui/label';

/**
 * Поле формы: подпись, контрол, подсказка и ошибка валидации. Не шире места,
 * что ему дали (min-w-0, колонка minmax(0,1fr)): иначе длинное значение
 * селекта раздвигает форму за край диалога.
 */
export function Field({
    label,
    htmlFor,
    hint,
    error,
    children,
    className = '',
}: {
    label: string;
    htmlFor?: string;
    hint?: React.ReactNode;
    error?: string;
    children: React.ReactNode;
    className?: string;
}) {
    return (
        // content-start: в ряду с более высоким соседом (с подсказкой) не растягиваться
        <div
            className={`grid min-w-0 grid-cols-[minmax(0,1fr)] content-start gap-1.5 ${className}`}
        >
            <Label htmlFor={htmlFor}>{label}</Label>
            {children}
            {hint && <p className="text-xs text-muted-foreground">{hint}</p>}
            <InputError message={error} />
        </div>
    );
}

/** Чекбокс с подписью, отправляется как 1/0 (правило boolean в Laravel). */
export function CheckField({
    name,
    label,
    hint,
    defaultChecked = false,
    onCheckedChange,
}: {
    name: string;
    label: string;
    hint?: React.ReactNode;
    defaultChecked?: boolean;
    onCheckedChange?: (checked: boolean) => void;
}) {
    return (
        <label className="flex items-start gap-3 text-sm">
            <input type="hidden" name={name} value="0" />
            <input
                type="checkbox"
                name={name}
                value="1"
                defaultChecked={defaultChecked}
                onChange={(e) => onCheckedChange?.(e.target.checked)}
                className="mt-0.5 size-4 rounded border-input accent-primary"
            />
            <span className="grid gap-0.5">
                <span className="font-medium">{label}</span>
                {hint && (
                    <span className="text-xs text-muted-foreground">
                        {hint}
                    </span>
                )}
            </span>
        </label>
    );
}
