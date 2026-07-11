import NProgress from "nprogress";
import "nprogress/nprogress.css";

NProgress.configure({
  easing: "ease",
  speed: 220,
  showSpinner: false,
  trickleSpeed: 160,
  minimum: 0.12
});

export default NProgress;
