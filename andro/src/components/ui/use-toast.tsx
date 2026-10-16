import * as React from 'react';
import {
  Toast,
  ToastClose,
  ToastDescription,
  ToastProvider,
  ToastTitle,
  ToastViewport,
} from '@/components/ui/toast';

type ToastMessage = {
  id: string;
  title: string;
  description?: string;
};

type ToastContextValue = {
  toast: (props: { title: string; description?: string }) => void;
};

const ToastContext = React.createContext<ToastContextValue | null>(null);

export function useToast() {
  const ctx = React.useContext(ToastContext);
  if (!ctx) {
    throw new Error('useToast must be used within Toaster');
  }
  return ctx;
}

export function Toaster({ children }: { children: React.ReactNode }) {
  const [messages, setMessages] = React.useState<ToastMessage[]>([]);

  const toast = React.useCallback((props: { title: string; description?: string }) => {
    const id = crypto.randomUUID();
    setMessages((prev) => [...prev, { id, ...props }]);
    setTimeout(() => {
      setMessages((prev) => prev.filter((m) => m.id !== id));
    }, 3000);
  }, []);

  return (
    <ToastContext.Provider value={{ toast }}>
      <ToastProvider>
        {children}
        {messages.map((message) => (
          <Toast key={message.id} open>
            <div className="grid gap-1">
              <ToastTitle>{message.title}</ToastTitle>
              {message.description && <ToastDescription>{message.description}</ToastDescription>}
            </div>
            <ToastClose />
          </Toast>
        ))}
        <ToastViewport />
      </ToastProvider>
    </ToastContext.Provider>
  );
}
