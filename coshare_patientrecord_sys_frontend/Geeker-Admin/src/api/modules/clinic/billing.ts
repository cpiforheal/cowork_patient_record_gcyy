import { authHeaders } from "@/api/modules/authToken";
import { clinicFetch, parseClinicApiResponse, clinicResponse } from "./http";

export interface BillingPatientInfo {
  id: string;
  patientName: string;
  identityNumber: string;
  address: string;
  phone: string;
  updatedAt: string;
}

export interface BillingPatientList {
  patients: BillingPatientInfo[];
  total: number;
}

/** 收费室专用：患者姓名、身份证号、家庭住址、联系电话（只读）。 */
export const getBillingPatientsApi = async (keyword: string) => {
  const query = keyword ? `?keyword=${encodeURIComponent(keyword)}` : "";
  const result = await clinicFetch(`/billing/patients${query}`, { headers: authHeaders() });
  const data = await parseClinicApiResponse<BillingPatientList>(result);
  return clinicResponse(data);
};
