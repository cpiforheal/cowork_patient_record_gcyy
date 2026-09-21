import type { InjectionKey, Ref } from "vue";

// inspira-ui Apple Card Carousel 上下文（1:1 移植）
export interface CarouselContextType {
  onCardClose: (index: number) => void;
  currentIndex: Ref<number>;
}

export const CarouselKey = Symbol() as InjectionKey<CarouselContextType>;
