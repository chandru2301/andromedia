import androLogo from '@/assets/andro-logo.png';

export function AndroLogo() {
  return (
    <img
      src={androLogo}
      alt="andro"
      className="h-5 w-auto [[data-theme='light']_&]:brightness-0 [[data-theme='light']_&]:opacity-80"
    />
  );
}
