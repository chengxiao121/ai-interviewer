/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

/** html2pdf.js 未随包发布 TS 类型，按实际用法声明链式 API */
declare module 'html2pdf.js' {
  interface Html2PdfWorker {
    set(options: Record<string, unknown>): Html2PdfWorker
    from(element: HTMLElement | string): Html2PdfWorker
    save(): Promise<void>
  }
  function html2pdf(): Html2PdfWorker
  export default html2pdf
}
