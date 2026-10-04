// Credit: https://usehooks-ts.com/
import { useState } from 'react';

export type CopiedValue = string | null;
export type CopyFn = (text: string) => Promise<boolean>;
export type UseClipboardReturn = [CopiedValue, CopyFn];

/**
 * navigator.clipboard есть только на https и localhost, а интерфейс часто
 * открывают по http во внутренней сети — тогда копируем через выделение.
 */
function legacyCopy(text: string): boolean {
    const area = document.createElement('textarea');
    area.value = text;
    area.setAttribute('readonly', '');
    area.style.position = 'fixed';
    area.style.opacity = '0';

    // внутрь открытого диалога: его ловушка фокуса не даст выделить текст снаружи
    const host =
        document.activeElement?.closest('[role="dialog"]') ?? document.body;
    const previous = document.activeElement as HTMLElement | null;
    host.appendChild(area);
    area.select();

    try {
        return document.execCommand('copy');
    } catch {
        return false;
    } finally {
        area.remove();
        previous?.focus();
    }
}

export function useClipboard(): UseClipboardReturn {
    const [copiedText, setCopiedText] = useState<CopiedValue>(null);

    const copy: CopyFn = async (text) => {
        try {
            if (window.isSecureContext && navigator.clipboard) {
                await navigator.clipboard.writeText(text);
            } else if (!legacyCopy(text)) {
                throw new Error('execCommand("copy") failed');
            }

            setCopiedText(text);

            return true;
        } catch (error) {
            console.warn('Copy failed', error);
            setCopiedText(null);

            return false;
        }
    };

    return [copiedText, copy];
}
