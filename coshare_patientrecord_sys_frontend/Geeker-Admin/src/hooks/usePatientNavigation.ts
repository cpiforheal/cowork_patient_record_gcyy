import { useRouter, type LocationQueryRaw } from "vue-router";

export const usePatientNavigation = () => {
  const router = useRouter();

  const openPatientDetail = (id: string, query?: LocationQueryRaw) => {
    if (!id) return;

    router.push({
      path: `/patients/detail/${encodeURIComponent(id)}`,
      query
    });
  };

  return {
    openPatientDetail
  };
};
