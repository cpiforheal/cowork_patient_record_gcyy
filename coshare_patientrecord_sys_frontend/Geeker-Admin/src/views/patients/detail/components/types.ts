export type FieldIssue = {
  fieldKey: string;

  fieldLabel: string;

  sectionKey: string;

  sectionTitle: string;

  message: string;

  level: "missing" | "invalid";
};

export type FollowupRecord = {
  id: string;

  date: string;

  type: string;

  node: string;

  project: string;

  management: string;

  imagingRequirement: string;

  completed: string;

  recovery: string;

  abnormal: string;

  advice: string;

  nextDate: string;

  onTime: string;
};
